package com.jetpackduba.gitnuro.managers

import com.jetpackduba.gitnuro.di.qualifiers.AppCoroutineScope
import com.jetpackduba.gitnuro.repositories.AppSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppStateManager @Inject constructor(
    private val appSettingsRepository: AppSettingsRepository,
    @AppCoroutineScope val appScope: CoroutineScope,
) {
    private val mutex = Mutex()

    private val _latestOpenedRepositoriesPaths = MutableStateFlow<List<String>>(emptyList())
    val latestOpenedRepositoriesPaths = _latestOpenedRepositoriesPaths.asStateFlow()

    val latestOpenedRepositoryPath: String
        get() = _latestOpenedRepositoriesPaths.value.firstOrNull() ?: ""

    fun repositoryTabChanged(path: String) = appScope.launch(Dispatchers.IO) {
        mutex.lock()
        try {
            val repoPaths = _latestOpenedRepositoriesPaths.value.toMutableList()

            // Remove any previously existing path
            repoPaths.removeIf { it == path }

            // Add the latest one to the beginning
            repoPaths.add(0, path)

            appSettingsRepository.latestOpenedRepositoriesPath = Json.encodeToString(repoPaths)
            _latestOpenedRepositoriesPaths.value = repoPaths
        } finally {
            mutex.unlock()
        }
    }

    fun loadRepositoriesTabs() {
        val repoPaths = _latestOpenedRepositoriesPaths.value.toMutableList()

        // 1) Recently opened repositories list (used by Welcome/Open popup)
        val repositoriesPathsSaved = appSettingsRepository.latestOpenedRepositoriesPath
        if (repositoriesPathsSaved.isNotEmpty()) {
            val repositories = Json.decodeFromString<List<String>>(repositoriesPathsSaved)
                .filter { it.isNotBlank() }

            for (path in repositories) {
                if (!repoPaths.contains(path)) repoPaths.add(path)
            }
        }

        // 2) Persisted tabs (so repositories are visible immediately without switching tabs)
        val tabsSaved = appSettingsRepository.latestTabsOpened
        if (tabsSaved.isNotEmpty()) {
            val tabRepositories = Json.decodeFromString<List<String>>(tabsSaved)
                .filter { it.isNotBlank() }

            for (path in tabRepositories) {
                if (!repoPaths.contains(path)) repoPaths.add(path)
            }
        }

        _latestOpenedRepositoriesPaths.value = repoPaths
    }

    fun cancelCoroutines() {
        appScope.cancel("Closing app")
    }

    fun removeRepositoryFromRecent(path: String) = appScope.launch {
        mutex.lock()
        try {
            val repoPaths = _latestOpenedRepositoriesPaths.value.toMutableList()
            repoPaths.removeIf { it == path }

            appSettingsRepository.latestOpenedRepositoriesPath = Json.encodeToString(repoPaths)
            _latestOpenedRepositoriesPaths.value = repoPaths
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Ensures the given repositories are visible in the "recent/open" list immediately.
     * This is used to show repositories that exist as tabs even if they haven't been opened (selected) yet.
     */
    fun ensureRepositoriesKnown(paths: List<String>) = appScope.launch(Dispatchers.IO) {
        val cleaned = paths.filter { it.isNotBlank() }
        if (cleaned.isEmpty()) return@launch

        mutex.lock()
        try {
            val repoPaths = _latestOpenedRepositoriesPaths.value.toMutableList()
            var changed = false

            for (path in cleaned) {
                if (!repoPaths.contains(path)) {
                    repoPaths.add(path)
                    changed = true
                }
            }

            if (changed) {
                appSettingsRepository.latestOpenedRepositoriesPath = Json.encodeToString(repoPaths)
                _latestOpenedRepositoriesPaths.value = repoPaths
            }
        } finally {
            mutex.unlock()
        }
    }
}