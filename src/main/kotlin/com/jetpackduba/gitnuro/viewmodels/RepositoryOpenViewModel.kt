package com.jetpackduba.gitnuro.viewmodels

import com.jetpackduba.gitnuro.SharedRepositoryStateManager
import com.jetpackduba.gitnuro.TaskType
import com.jetpackduba.gitnuro.exceptions.codeToMessage
import com.jetpackduba.gitnuro.git.*
import com.jetpackduba.gitnuro.git.branches.CheckoutRefUseCase
import com.jetpackduba.gitnuro.git.branches.CreateBranchUseCase
import com.jetpackduba.gitnuro.git.remotes.AddRemoteUseCase
import com.jetpackduba.gitnuro.git.remotes.UpdateRemoteUseCase
import com.jetpackduba.gitnuro.git.rebase.RebaseInteractiveState
import com.jetpackduba.gitnuro.git.stash.StashChangesUseCase
import com.jetpackduba.gitnuro.git.workspace.StageUntrackedFileUseCase
import com.jetpackduba.gitnuro.logging.printDebug
import com.jetpackduba.gitnuro.logging.printLog
import com.jetpackduba.gitnuro.managers.AppStateManager
import com.jetpackduba.gitnuro.managers.ErrorsManager
import com.jetpackduba.gitnuro.managers.newErrorNow
import com.jetpackduba.gitnuro.models.AuthorInfoSimple
import com.jetpackduba.gitnuro.models.errorNotification
import com.jetpackduba.gitnuro.models.positiveNotification
import com.jetpackduba.gitnuro.system.OpenFilePickerUseCase
import com.jetpackduba.gitnuro.system.OpenUrlInBrowserUseCase
import com.jetpackduba.gitnuro.system.PickerType
import com.jetpackduba.gitnuro.ui.IVerticalSplitPaneConfig
import com.jetpackduba.gitnuro.ui.SelectedItem
import com.jetpackduba.gitnuro.ui.TabsManager
import com.jetpackduba.gitnuro.ui.VerticalSplitPaneConfig
import com.jetpackduba.gitnuro.updates.Update
import com.jetpackduba.gitnuro.updates.UpdatesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.eclipse.jgit.api.ListBranchCommand
import org.eclipse.jgit.api.RemoteSetUrlCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.CheckoutConflictException
import org.eclipse.jgit.blame.BlameResult
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.RepositoryState
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.transport.RefSpec
import java.awt.Desktop
import java.io.File
import javax.inject.Inject
import javax.inject.Provider

private const val MIN_TIME_AFTER_GIT_OPERATION = 2000L

private const val TAG = "TabViewModel"

/**
 * Contains all the information related to a tab and its subcomponents (smaller composables like the log, branches,
 * commit changes, etc.). It holds a reference to every view model because this class lives as long as the tab is open (survives
 * across full app recompositions), therefore, tab's content can be recreated with these view models.
 */
class RepositoryOpenViewModel @Inject constructor(
    private val getWorkspacePathUseCase: GetWorkspacePathUseCase,
    private val diffViewModelProvider: Provider<DiffViewModel>,
    private val historyViewModelProvider: Provider<HistoryViewModel>,
    private val authorViewModelProvider: Provider<AuthorViewModel>,
    private val tabState: TabState,
    val appStateManager: AppStateManager,
    private val fileChangesWatcher: FileChangesWatcher,
    private val getAuthorInfoUseCase: GetAuthorInfoUseCase,
    private val createBranchUseCase: CreateBranchUseCase,
    private val checkoutRefUseCase: CheckoutRefUseCase,
    private val addRemoteUseCase: AddRemoteUseCase,
    private val updateRemoteUseCase: UpdateRemoteUseCase,
    private val stashChangesUseCase: StashChangesUseCase,
    private val stageUntrackedFileUseCase: StageUntrackedFileUseCase,
    private val openFilePickerUseCase: OpenFilePickerUseCase,
    private val openUrlInBrowserUseCase: OpenUrlInBrowserUseCase,
    private val tabsManager: TabsManager,
    private val tabScope: CoroutineScope,
    private val verticalSplitPaneConfig: VerticalSplitPaneConfig,
    val tabViewModelsProvider: ViewModelsProvider,
    private val globalMenuActionsViewModel: GlobalMenuActionsViewModel,
    sharedRepositoryStateManager: SharedRepositoryStateManager,
    updatesRepository: UpdatesRepository,
) : IVerticalSplitPaneConfig by verticalSplitPaneConfig,
    IGlobalMenuActionsViewModel by globalMenuActionsViewModel {
    private val errorsManager: ErrorsManager = tabState.errorsManager

    val selectedItem: StateFlow<SelectedItem> = tabState.selectedItem
    var diffViewModel: DiffViewModel? = null

    val repositoryState: StateFlow<RepositoryState> = sharedRepositoryStateManager.repositoryState
    val rebaseInteractiveState: StateFlow<RebaseInteractiveState> = sharedRepositoryStateManager.rebaseInteractiveState

    private val _diffSelected = MutableStateFlow<DiffType?>(null)
    val diffSelected: StateFlow<DiffType?> = _diffSelected

    var newDiffSelected: DiffType?
        get() = diffSelected.value
        set(value) {
            _diffSelected.value = value
            updateDiffEntry()
        }

    private val _blameState = MutableStateFlow<BlameState>(BlameState.None)
    val blameState: StateFlow<BlameState> = _blameState

    private val _showHistory = MutableStateFlow(false)
    val showHistory: StateFlow<Boolean> = _showHistory

    private val _showAuthorInfo = MutableStateFlow(false)
    val showAuthorInfo: StateFlow<Boolean> = _showAuthorInfo

    private val _authorInfoSimple = MutableStateFlow(AuthorInfoSimple(null, null))
    val authorInfoSimple: StateFlow<AuthorInfoSimple> = _authorInfoSimple

    var historyViewModel: HistoryViewModel? = null
        private set

    var authorViewModel: AuthorViewModel? = null
        private set

    private var hasGitDirChanged = false

    init {
        tabScope.run {
            launch {
                tabState.refreshFlowFiltered(RefreshType.ALL_DATA, RefreshType.REPO_STATE) {
                    loadAuthorInfo(tabState.git)
                }
            }

            launch {
                watchRepositoryChanges(tabState.git)
            }
        }
    }


    /**
     * To make sure the tab opens the new repository with a clean state,
     * instead of opening the repo in the same ViewModel we simply create a new tab with a new TabViewModel
     * replacing the current tab
     */
    fun openAnotherRepository(directory: String) = tabState.runOperation(
        showError = true,
        refreshType = RefreshType.NONE,
    ) { git ->
        tabsManager.addNewTabFromPath(directory, true, getWorkspacePathUseCase(git))
    }

    private suspend fun loadAuthorInfo(git: Git) {
        _authorInfoSimple.value = getAuthorInfoUseCase(git)
    }

    fun showAuthorInfoDialog() {
        authorViewModel = authorViewModelProvider.get()
        authorViewModel?.loadAuthorInfo()
        _showAuthorInfo.value = true
    }

    fun closeAuthorInfoDialog() {
        _showAuthorInfo.value = false
        authorViewModel = null
    }

    /**
     * Sometimes external apps can run filesystem multiple operations in a fraction of a second.
     * To prevent excessive updates, we add a slight delay between updates emission to prevent slowing down
     * the app by constantly running "git status" or even full refreshes.
     *
     */
    private suspend fun watchRepositoryChanges(git: Git) = tabScope.launch(Dispatchers.IO) {
        launch {
            fileChangesWatcher.changesNotifier.collect { watcherEvent ->
                when (watcherEvent) {
                    is WatcherEvent.RepositoryChanged -> repositoryChanged(watcherEvent.hasGitDirChanged)
                    is WatcherEvent.WatchInitError -> {
                        val message = codeToMessage(watcherEvent.code)
                        errorsManager.addError(
                            newErrorNow(
                                exception = Exception(message),
                                taskType = TaskType.CHANGES_DETECTION,
                            ),
                        )

                    }
                }
            }
        }

        fileChangesWatcher.watchDirectoryPath(git.repository)
    }

    private suspend fun CoroutineScope.repositoryChanged(hasGitDirChanged: Boolean) {
        val isOperationRunning = tabState.operationRunning

        if (!isOperationRunning) { // Only update if there isn't any process running
            printDebug(TAG, "Detected changes in the repository's directory")

            val currentTimeMillis = System.currentTimeMillis()

            if (
                hasGitDirChanged &&
                currentTimeMillis - tabState.lastOperation < MIN_TIME_AFTER_GIT_OPERATION
            ) {
                printDebug(TAG, "Git operation was executed recently, ignoring file system change")
                return
            }

            if (hasGitDirChanged) {
                this@RepositoryOpenViewModel.hasGitDirChanged = true
            }

            if (isActive) {
                updateApp(hasGitDirChanged)
            }

            this@RepositoryOpenViewModel.hasGitDirChanged = false
        } else {
            printDebug(TAG, "Ignored file events during operation")
        }
    }

    private suspend fun updateApp(hasGitDirChanged: Boolean) {
        if (hasGitDirChanged) {
            printLog(TAG, "Changes detected in git directory, full refresh")

            refreshRepositoryInfo()
        } else {
            printLog(TAG, "Changes detected, partial refresh")

            checkUncommittedChanges()
        }
    }

    private suspend fun checkUncommittedChanges() = tabState.runOperation(
        refreshType = RefreshType.NONE,
    ) {
        updateDiffEntry()
        tabState.refreshData(RefreshType.UNCOMMITTED_CHANGES_AND_LOG)
    }

    private suspend fun refreshRepositoryInfo() {
        tabState.refreshData(RefreshType.ALL_DATA)
    }

    private fun updateDiffEntry() {
        val diffSelected = diffSelected.value

        if (diffSelected != null) {
            if (diffViewModel == null) { // Initialize the view model if required
                diffViewModel = diffViewModelProvider.get()
            }

            diffViewModel?.cancelRunningJobs()
            diffViewModel?.updateDiff(diffSelected)
        } else {
            diffViewModel?.close()
            diffViewModel = null // Free the view model from the memory if not being used.
        }
    }

    fun openDirectoryPicker(): String? {
        val latestDirectoryOpened = appStateManager.latestOpenedRepositoryPath

        return openFilePickerUseCase(PickerType.DIRECTORIES, latestDirectoryOpened)
    }

    val update: StateFlow<Update?> = updatesRepository.hasUpdatesFlow

    fun blameFile(filePath: String) = tabState.safeProcessing(
        refreshType = RefreshType.NONE,
        taskType = TaskType.BLAME_FILE,
    ) { git ->
        _blameState.value = BlameState.Loading(filePath)
        try {
            val result = git.blame()
                .setFilePath(filePath)
                .setFollowFileRenames(true)
                .call() ?: throw Exception("File is no longer present in the workspace and can't be blamed")

            _blameState.value = BlameState.Loaded(filePath, result)
        } catch (ex: Exception) {
            resetBlameState()

            throw ex
        }

        null
    }

    fun resetBlameState() {
        _blameState.value = BlameState.None
    }

    fun expandBlame() {
        val blameState = _blameState.value

        if (blameState is BlameState.Loaded && blameState.isMinimized) {
            _blameState.value = blameState.copy(isMinimized = false)
        }
    }

    fun minimizeBlame() {
        val blameState = _blameState.value

        if (blameState is BlameState.Loaded && !blameState.isMinimized) {
            _blameState.value = blameState.copy(isMinimized = true)
        }
    }

    fun selectCommit(commit: RevCommit) = tabState.runOperation(
        refreshType = RefreshType.NONE,
    ) {
        tabState.newSelectedCommit(commit)
    }

    fun fileHistory(filePath: String) {
        historyViewModel = historyViewModelProvider.get()
        historyViewModel?.fileHistory(filePath)
        _showHistory.value = true
    }

    fun closeHistory() {
        _showHistory.value = false
        historyViewModel = null
    }

    fun refreshAll() = tabScope.launch {
        printLog(TAG, "Manual refresh triggered. IS OPERATION RUNNING ${tabState.operationRunning}")
        if (!tabState.operationRunning) {
            refreshRepositoryInfo()
        }
    }

    fun createBranch(branchName: String) = tabState.safeProcessing(
        refreshType = RefreshType.ALL_DATA,
        refreshEvenIfCrashesInteractive = { it is CheckoutConflictException },
        taskType = TaskType.CREATE_BRANCH,
    ) { git ->
        createBranchUseCase(git, branchName)

        positiveNotification("Branch \"${branchName}\" created")
    }

    fun stashWithMessage(message: String) = tabState.safeProcessing(
        refreshType = RefreshType.UNCOMMITTED_CHANGES_AND_LOG,
        taskType = TaskType.STASH,
    ) { git ->
        stageUntrackedFileUseCase(git)

        if (stashChangesUseCase(git, message)) {
            positiveNotification("Changes stashed")
        } else {
            errorNotification("There are no changes to stash")
        }
    }

    fun openFolderInFileExplorer() = tabState.runOperation(
        showError = true,
        refreshType = RefreshType.NONE,
    ) { git ->
        Desktop.getDesktop().open(git.repository.workTree)
    }

    fun importBundle() {
        val repo = tabState.git.repository
        val selectedPath = openFilePickerUseCase(PickerType.FILES, repo.workTree.absolutePath) ?: return
        val bundleFile = File(selectedPath)
        val remoteName = buildBundleRemoteName(bundleFile)
        val remoteUri = bundleFile.toURI().toString()

        tabState.safeProcessing(
            refreshType = RefreshType.ALL_DATA,
            title = "Import bundle",
            subtitle = "Importing ${bundleFile.name}",
            taskType = TaskType.IMPORT_BUNDLE,
        ) { git ->
            ensureBundleRemote(git, remoteName, remoteUri)

            // Import as remote-tracking branches to avoid polluting local branches list.
            val refSpec = RefSpec("+refs/heads/*:refs/remotes/$remoteName/*")

            git.fetch()
                .setRemote(remoteName)
                .setRefSpecs(refSpec)
                .call()

            val importedRemoteBranches = git.branchList()
                .setListMode(ListBranchCommand.ListMode.REMOTE)
                .call()
                .filter { it.name.startsWith("refs/remotes/$remoteName/") }

            if (importedRemoteBranches.isEmpty()) {
                return@safeProcessing errorNotification(
                    "Bundle imported, but no branches were found inside it (no refs/heads)."
                )
            }

            val pickedBranch = pickBranchToCheckout(importedRemoteBranches)
            val pickedBranchName = Repository.shortenRefName(pickedBranch.name) // e.g. bundle_x/main
            val importedCount = importedRemoteBranches.size

            val isClean = git.status().call().isClean
            if (isClean) {
                val hasLocalBranches = git.branchList().call().isNotEmpty()
                if (!hasLocalBranches) {
                    // For an empty repo, create the branch (e.g. main) so the user lands on a normal branch.
                    checkoutRefUseCase(git, pickedBranch)
                    return@safeProcessing positiveNotification(
                        "Bundle imported as remote \"$remoteName\" ($importedCount branches) and checked out \"$pickedBranchName\""
                    )
                }

                // For existing repos, avoid creating extra local branches: restore files via detached checkout.
                git.checkout().setName(pickedBranch.objectId.name).call()
                return@safeProcessing positiveNotification(
                    "Bundle imported as remote \"$remoteName\" ($importedCount branches). " +
                        "Checked out \"$pickedBranchName\" (detached)"
                )
            }

            return@safeProcessing positiveNotification(
                "Bundle imported as remote \"$remoteName\" ($importedCount branches). " +
                    "To restore files, checkout \"$pickedBranchName\" (working tree is not clean)."
            )
        }
    }

    fun resolveConflicts() = tabScope.launch {
        tabState.newSelectedItem(SelectedItem.UncommittedChanges, scrollToItem = true)
    }

    private suspend fun ensureBundleRemote(git: Git, remoteName: String, remoteUri: String) {
        val existing = git.remoteList().call().any { it.name == remoteName }

        if (!existing) {
            addRemoteUseCase(git, remoteName, remoteUri)
        }

        // Always update URLs so re-importing the same named bundle works even if the file moved.
        updateRemoteUseCase(git, remoteName, remoteUri, RemoteSetUrlCommand.UriType.FETCH)
        updateRemoteUseCase(git, remoteName, remoteUri, RemoteSetUrlCommand.UriType.PUSH)
    }

    private fun buildBundleRemoteName(bundleFile: File): String {
        val sanitizedName = sanitizeName(bundleFile.nameWithoutExtension)
        val candidate = "bundle_${sanitizedName}".take(60).trimEnd('_')
        return candidate.ifBlank { "bundle_import" }
    }

    private fun pickBranchToCheckout(importedBranches: List<Ref>): Ref {
        val preferredSuffixes = listOf("/main", "/master", "/trunk", "/develop")

        for (suffix in preferredSuffixes) {
            importedBranches.firstOrNull { Repository.shortenRefName(it.name).endsWith(suffix) }?.let { return it }
        }

        return importedBranches.minBy { it.name }
    }

    private fun sanitizeName(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return "import"
        // Replace characters that commonly break ref names.
        val sanitized = trimmed.replace(Regex("""[^0-9A-Za-z._-]"""), "_")
        return sanitized.take(50).ifBlank { "import" }
    }

    fun openUrlInBrowser(url: String) {
        openUrlInBrowserUseCase(url)
    }

    fun closeLastView() = tabScope.launch {
        tabState.closeLastView()
    }
}


sealed interface BlameState {
    data class Loading(val filePath: String) : BlameState

    data class Loaded(val filePath: String, val blameResult: BlameResult, val isMinimized: Boolean = false) : BlameState

    data object None : BlameState
}