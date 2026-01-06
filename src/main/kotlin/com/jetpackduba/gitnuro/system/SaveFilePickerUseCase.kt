package com.jetpackduba.gitnuro.system

import com.jetpackduba.gitnuro.logging.printLog
import com.jetpackduba.gitnuro.managers.ShellManager
import java.awt.FileDialog
import java.io.File
import javax.inject.Inject
import javax.swing.JFileChooser
import javax.swing.UIManager

private const val TAG = "SystemDialogs"

/**
 * Shows a "save as" picker dialog to select a file destination.
 *
 * NOTE: This is intentionally separated from [OpenFilePickerUseCase] to avoid mixing open/save semantics.
 */
class SaveFilePickerUseCase @Inject constructor(
    /**
     * We want specifically [ShellManager] implementation and not [com.jetpackduba.gitnuro.managers.IShellManager],
     * to run commands without any modification
     * (such as ones done by [com.jetpackduba.gitnuro.managers.FlatpakShellManager], because it has to run in the sandbox)
     */
    private val shellManager: ShellManager,
) {
    operator fun invoke(
        defaultFileName: String?,
        basePath: String?,
    ): String? {
        val isLinux = currentOs.isLinux()
        val isMac = currentOs.isMac()

        return if (isLinux) {
            saveFileDialogLinux(defaultFileName)
        } else {
            saveJvmDialog(defaultFileName, basePath, isMac)
        }
    }

    private fun saveFileDialogLinux(defaultFileName: String?): String? {
        var fileToSave: String? = null

        val checkZenityInstalled = shellManager.runCommand(listOf("which", "zenity", "2>/dev/null"))
        val isZenityInstalled = !checkZenityInstalled.isNullOrEmpty()

        printLog(TAG, "IsZenityInstalled $isZenityInstalled")

        if (isZenityInstalled) {
            val command = buildList {
                add("zenity")
                add("--file-selection")
                add("--save")
                add("--confirm-overwrite")
                add("--title=Save")
                if (!defaultFileName.isNullOrBlank()) {
                    add("--filename=$defaultFileName")
                }
            }

            val saveFile = shellManager.runCommand(command)?.replace("\n", "")
            if (!saveFile.isNullOrEmpty()) {
                fileToSave = saveFile
            }
        } else {
            fileToSave = saveJvmDialog(defaultFileName, basePath = null, isMac = false)
        }

        return fileToSave
    }

    private fun saveJvmDialog(
        defaultFileName: String?,
        basePath: String?,
        isMac: Boolean,
    ): String? {
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())

        if (isMac) {
            val fileChooser = FileDialog(null as java.awt.Frame?, "Save", FileDialog.SAVE).apply {
                if (!basePath.isNullOrBlank()) {
                    directory = basePath
                }

                if (!defaultFileName.isNullOrBlank()) {
                    file = defaultFileName
                }
            }

            fileChooser.isMultipleMode = false
            fileChooser.isVisible = true

            if (fileChooser.file != null && fileChooser.directory != null) {
                return fileChooser.directory + fileChooser.file
            }

            return null
        } else {
            val fileChooser = if (basePath.isNullOrEmpty()) {
                JFileChooser()
            } else {
                JFileChooser(basePath)
            }

            fileChooser.fileSelectionMode = JFileChooser.FILES_ONLY
            if (!defaultFileName.isNullOrBlank()) {
                val initial = if (basePath.isNullOrBlank()) {
                    File(defaultFileName)
                } else {
                    File(basePath, defaultFileName)
                }
                fileChooser.selectedFile = initial
            }

            val result = fileChooser.showSaveDialog(null)
            return if (result == JFileChooser.APPROVE_OPTION && fileChooser.selectedFile != null) {
                fileChooser.selectedFile.absolutePath
            } else {
                null
            }
        }
    }
}

