package com.jetpackduba.gitnuro.git.archive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject

class ArchiveCommitUseCase @Inject constructor() {
    /**
     * Creates a ZIP archive of the repository state at [commitId].
     *
     * @return number of files written into the archive
     */
    suspend operator fun invoke(
        git: Git,
        commitId: ObjectId,
        outputZip: File,
    ): Int = withContext(Dispatchers.IO) {
        val repo = git.repository
        val outputFile = outputZip.absoluteFile
        outputFile.parentFile?.mkdirs()

        RevWalk(repo).use { revWalk ->
            val commit = revWalk.parseCommit(commitId)
            val shortSha = commitId.name.take(7)
            val rootFolder = "${repo.workTree.name}-$shortSha"

            ZipOutputStream(BufferedOutputStream(FileOutputStream(outputFile))).use { zip ->
                zip.setLevel(6)

                val treeWalk = TreeWalk(repo).apply {
                    addTree(commit.tree)
                    isRecursive = true
                }

                var filesCount = 0
                while (treeWalk.next()) {
                    val path = treeWalk.pathString
                    val blobId = treeWalk.getObjectId(0)
                    val loader = repo.open(blobId)

                    val entry = ZipEntry("$rootFolder/$path").apply {
                        time = commit.commitTime.toLong() * 1000L
                    }

                    zip.putNextEntry(entry)
                    loader.openStream().use { input ->
                        input.copyTo(zip)
                    }
                    zip.closeEntry()
                    filesCount++
                }

                return@withContext filesCount
            }
        }
    }
}

