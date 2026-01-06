package com.jetpackduba.gitnuro.git.bundle

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.NullProgressMonitor
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.BundleWriter
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

data class BundleExportResult(
    val includedRefsCount: Int,
    val outputFile: File,
)

/**
 * Creates a git bundle that contains [baseCommitId] and all its descendants reachable from local branches.
 *
 * This roughly matches Mercurial/TortoiseHg "bundle revision and its descendants" semantics.
 *
 * IMPORTANT: The bundle is incremental: it assumes all parents of [baseCommitId] are already present in the target repo.
 */
class CreateBundleFromCommitUseCase @Inject constructor() {
    suspend operator fun invoke(
        git: Git,
        baseCommitId: ObjectId,
        outputBundle: File,
    ): BundleExportResult = withContext(Dispatchers.IO) {
        val repo = git.repository
        val outputFile = outputBundle.absoluteFile
        outputFile.parentFile?.mkdirs()

        RevWalk(repo).use { revWalk ->
            val baseCommit = revWalk.parseCommit(baseCommitId)

            val localBranches = git.branchList().call()
            val descendantBranchRefs = localBranches.filter { ref ->
                val tipCommit = ref.objectId?.let { revWalk.parseCommit(it) } ?: return@filter false
                revWalk.isMergedInto(baseCommit, tipCommit)
            }

            val bundleWriter = BundleWriter(repo)

            // Prerequisites (exclude history before base commit; include base commit and descendants)
            assumeParents(bundleWriter, baseCommit)

            if (descendantBranchRefs.isNotEmpty()) {
                descendantBranchRefs.forEach { ref ->
                    // Keep original ref names inside the bundle (refs/heads/<name>)
                    bundleWriter.include(ref.name, ref.objectId)
                }
            } else {
                // Fallback: include the base commit itself as a synthetic branch in the bundle
                val shortSha = baseCommitId.name.take(7)
                bundleWriter.include("refs/heads/bundle-base/$shortSha", baseCommitId)
            }

            BufferedOutputStream(FileOutputStream(outputFile)).use { out ->
                bundleWriter.writeBundle(NullProgressMonitor.INSTANCE, out)
            }

            BundleExportResult(
                includedRefsCount = maxOf(1, descendantBranchRefs.size),
                outputFile = outputFile,
            )
        }
    }

    private fun assumeParents(bundleWriter: BundleWriter, baseCommit: RevCommit) {
        // If baseCommit has no parents (root commit), this makes the bundle self-contained.
        baseCommit.parents.forEach { parent ->
            bundleWriter.assume(parent)
        }
    }
}

