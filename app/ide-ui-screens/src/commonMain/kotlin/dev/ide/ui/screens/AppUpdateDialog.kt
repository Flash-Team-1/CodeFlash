package dev.ide.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.FileActions
import dev.ide.ui.backend.UiAppUpdate
import dev.ide.ui.components.LinearProgressIndicator
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.update_download
import dev.ide.ui.generated.resources.update_downloading
import dev.ide.ui.generated.resources.update_failed
import dev.ide.ui.generated.resources.update_later
import dev.ide.ui.generated.resources.update_no_notes
import dev.ide.ui.generated.resources.update_progress
import dev.ide.ui.generated.resources.update_title
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * The update prompt: what is new, then a download that runs inside the app with progress, then the system
 * installer. Where the host cannot fetch a file, the button opens the link in the browser instead.
 */
@Composable
fun AppUpdateDialog(update: UiAppUpdate, fileActions: FileActions, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var failed by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(Res.string.update_title, update.versionName)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(update.notes?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.update_no_notes))
                if (busy) {
                    LinearProgressIndicator(
                        progress = { progress.coerceAtLeast(0f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(stringResource(Res.string.update_progress, (progress.coerceAtLeast(0f) * 100).toInt()))
                }
                if (failed) Text(stringResource(Res.string.update_failed))
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    if (!fileActions.canDownloadFile) {
                        fileActions.openUrl(update.downloadUrl)
                        onDismiss()
                        return@TextButton
                    }
                    busy = true
                    failed = false
                    scope.launch {
                        val path = fileActions.downloadFile(update.downloadUrl, "update-${update.versionCode}.apk") {
                            progress = it
                        }
                        busy = false
                        if (path == null) {
                            failed = true
                        } else {
                            onDismiss()
                            fileActions.installDownloadedApk(path)
                        }
                    }
                },
            ) {
                Text(stringResource(if (busy) Res.string.update_downloading else Res.string.update_download))
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(Res.string.update_later)) }
        },
    )
}
