package dev.ide.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.FileActions
import dev.ide.ui.backend.UiAppUpdate
import dev.ide.ui.components.LinearProgressIndicator
import dev.ide.ui.components.PrimaryActionButton
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.update_download
import dev.ide.ui.generated.resources.update_downloading
import dev.ide.ui.generated.resources.update_failed
import dev.ide.ui.generated.resources.update_later
import dev.ide.ui.generated.resources.update_mandatory_note
import dev.ide.ui.generated.resources.update_no_notes
import dev.ide.ui.generated.resources.update_progress
import dev.ide.ui.generated.resources.update_title
import dev.ide.ui.icons.CaSymbols
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * The update sheet. Same shape as the store sign-in sheet: a bottom sheet on the app's theme, with the
 * download running inside the app. A required update cannot be swiped, tapped outside or backed out of.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppUpdateSheet(update: UiAppUpdate, fileActions: FileActions, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val mandatory = update.mandatory
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(-1f) }
    var failed by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { !mandatory || it != SheetValue.Hidden },
    )

    fun start() {
        if (busy) return
        if (!fileActions.canDownloadFile) {
            // The host cannot fetch the file itself, so the link opens in a browser instead.
            fileActions.openUrl(update.downloadUrl)
            if (!mandatory) onDismiss()
            return
        }
        busy = true
        failed = false
        progress = -1f
        scope.launch {
            val path = fileActions.downloadFile(update.downloadUrl, "update-${update.versionCode}.apk") { progress = it }
            busy = false
            if (path == null) {
                failed = true
            } else {
                fileActions.installDownloadedApk(path)
                if (!mandatory) onDismiss()
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!busy && !mandatory) onDismiss() },
        sheetState = sheetState,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = !mandatory && !busy),
    ) {
        // The sheet opens at full height. The text scrolls in the space above and the actions stay pinned
        // underneath, so a long "What's new" never pushes the download button off the screen.
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(0.92f).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                UpdateHeading(stringResource(Res.string.update_title, update.versionName))
                Spacer(Modifier.height(10.dp))
                UpdateBody(update.notes?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.update_no_notes))
                if (mandatory) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(Res.string.update_mandatory_note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                    )
                }
                if (busy) {
                    Spacer(Modifier.height(18.dp))
                    if (progress >= 0f) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(6.dp))
                        UpdateBody(stringResource(Res.string.update_progress, (progress * 100).toInt()))
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
                if (failed) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        stringResource(Res.string.update_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
            PrimaryActionButton(
                label = stringResource(if (busy) Res.string.update_downloading else Res.string.update_download),
                glyph = CaSymbols.download,
                onClick = { start() },
                modifier = Modifier.fillMaxWidth(),
            )
            if (!mandatory) {
                Spacer(Modifier.height(6.dp))
                TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(Res.string.update_later)) }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun UpdateHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun UpdateBody(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}
