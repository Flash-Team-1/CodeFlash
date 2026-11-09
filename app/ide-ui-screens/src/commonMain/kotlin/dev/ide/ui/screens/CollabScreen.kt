package dev.ide.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.ProjectInfo
import dev.ide.ui.backend.UiCollabCandidate
import dev.ide.ui.backend.UiCollabChange
import dev.ide.ui.backend.UiCollabCommit
import dev.ide.ui.backend.UiCollabDiff
import dev.ide.ui.backend.UiCollabInvite
import dev.ide.ui.backend.UiCollabProject
import dev.ide.ui.backend.UiCollabStatus
import dev.ide.ui.components.PrimaryActionButton
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.collab_accept
import dev.ide.ui.generated.resources.collab_accepted
import dev.ide.ui.generated.resources.collab_after
import dev.ide.ui.generated.resources.collab_back
import dev.ide.ui.generated.resources.collab_before
import dev.ide.ui.generated.resources.collab_by
import dev.ide.ui.generated.resources.collab_cancel
import dev.ide.ui.generated.resources.collab_candidate_action
import dev.ide.ui.generated.resources.collab_candidates_empty
import dev.ide.ui.generated.resources.collab_candidates_title
import dev.ide.ui.generated.resources.collab_change_added
import dev.ide.ui.generated.resources.collab_change_deleted
import dev.ide.ui.generated.resources.collab_change_modified
import dev.ide.ui.generated.resources.collab_changes
import dev.ide.ui.generated.resources.collab_decline
import dev.ide.ui.generated.resources.collab_declined
import dev.ide.ui.generated.resources.collab_history
import dev.ide.ui.generated.resources.collab_invite
import dev.ide.ui.generated.resources.collab_invited
import dev.ide.ui.generated.resources.collab_invited_by
import dev.ide.ui.generated.resources.collab_invites_title
import dev.ide.ui.generated.resources.collab_joined
import dev.ide.ui.generated.resources.collab_linked_as
import dev.ide.ui.generated.resources.collab_linked_to
import dev.ide.ui.generated.resources.collab_more_changes
import dev.ide.ui.generated.resources.collab_my_shared
import dev.ide.ui.generated.resources.collab_no_changes
import dev.ide.ui.generated.resources.collab_no_commits
import dev.ide.ui.generated.resources.collab_no_files
import dev.ide.ui.generated.resources.collab_not_shared
import dev.ide.ui.generated.resources.collab_pull
import dev.ide.ui.generated.resources.collab_pulled
import dev.ide.ui.generated.resources.collab_push
import dev.ide.ui.generated.resources.collab_push_hint
import dev.ide.ui.generated.resources.collab_push_title
import dev.ide.ui.generated.resources.collab_pushed
import dev.ide.ui.generated.resources.collab_share_action
import dev.ide.ui.generated.resources.collab_shared_done
import dev.ide.ui.generated.resources.collab_this_folder
import dev.ide.ui.generated.resources.collab_title
import dev.ide.ui.generated.resources.collab_use_here
import dev.ide.ui.generated.resources.collab_verified_only
import dev.ide.ui.generated.resources.collab_working
import dev.ide.ui.icons.CaSymbols
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Lines of a file shown in a diff before the rest is cut off. */
private const val MAX_DIFF_LINES = 400

/** Shared projects: share this folder, join one shared with you, pull, push, invite, and read the history. */
@Composable
fun CollabScreen(backend: IdeBackend, project: ProjectInfo, onBack: () -> Unit, onOpenProject: (String) -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val sharedText = stringResource(Res.string.collab_shared_done)
    val joinedText = stringResource(Res.string.collab_joined)
    val invitedText = stringResource(Res.string.collab_invited)
    val acceptedText = stringResource(Res.string.collab_accepted)
    val declinedText = stringResource(Res.string.collab_declined)
    val c = MaterialTheme.colorScheme

    var status by remember(project.rootPath) { mutableStateOf<UiCollabStatus?>(null) }
    var projects by remember { mutableStateOf<List<UiCollabProject>>(emptyList()) }
    var invites by remember { mutableStateOf<List<UiCollabInvite>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var noticeIsError by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    var showInvite by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var commits by remember { mutableStateOf<List<UiCollabCommit>>(emptyList()) }
    var openCommit by remember { mutableStateOf<UiCollabCommit?>(null) }
    var diffs by remember { mutableStateOf<List<UiCollabDiff>>(emptyList()) }
    // Only verified accounts can invite (the server checks it too); everyone else never sees the button.
    var verified by remember { mutableStateOf(false) }

    LaunchedEffect(project.rootPath, reload) {
        status = backend.store.collabSyncStatus(project.rootPath)
        projects = backend.store.collabProjects()
        invites = backend.store.collabInvites()
        verified = runCatching { backend.store.myProfile() }.getOrNull()?.verified == true
    }
    val projectId = status?.projectId
    LaunchedEffect(projectId, showHistory, reload) {
        commits = if (showHistory && projectId != null) backend.store.collabLog(projectId) else emptyList()
    }

    // Runs one action, shows its result, and reloads everything the screen shows.
    // Accepting makes a folder for the shared project, downloads it, and opens it in the editor.
    suspend fun acceptShared(inv: UiCollabInvite): String? {
        backend.store.collabRespond(inv.id, true)?.let { return it }
        val folder = backend.projects.createSharedFolder(inv.projectName) ?: return "Could not create a folder for the project"
        backend.store.collabJoin(folder, inv.projectId)?.let { return it }
        onOpenProject(folder)
        return null
    }

    fun act(success: String, work: suspend () -> String?) {
        scope.launch {
            busy = true
            val err = work()
            busy = false
            noticeIsError = err != null
            notice = err ?: success
            reload++
        }
    }

    fun share() {
        scope.launch {
            busy = true
            val r = backend.store.collabShare(project.rootPath, project.name)
            busy = false
            noticeIsError = r.error != null
            notice = r.error ?: sharedText
            reload++
        }
    }

    fun openDiff(commit: UiCollabCommit) {
        scope.launch {
            busy = true
            diffs = backend.store.collabCommitDetail(commit.id)
            busy = false
            openCommit = commit
        }
    }

    openCommit?.let { commit ->
        CommitDiffView(commit, diffs, onBack = { openCommit = null })
        return
    }

    val linked = projectId != null
    val linkedName = projects.firstOrNull { it.id == projectId }?.name
    val changes = status?.changes.orEmpty()

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
    ) {
        item("header") {
            TextButton(onClick = onBack) { Text(stringResource(Res.string.collab_back)) }
            Text(stringResource(Res.string.collab_title), style = MaterialTheme.typography.headlineSmall)
            Text(project.name, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(Res.string.collab_verified_only), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        }

        item("folder") {
            CollabCard {
                Text(stringResource(Res.string.collab_this_folder), style = MaterialTheme.typography.titleMedium)
                if (!linked) {
                    Text(stringResource(Res.string.collab_not_shared), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                    PrimaryActionButton(
                        label = stringResource(Res.string.collab_share_action),
                        glyph = CaSymbols.share,
                        onClick = { share() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(
                        linkedName?.let { stringResource(Res.string.collab_linked_as, it) }
                            ?: stringResource(Res.string.collab_linked_to),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        if (changes.isEmpty()) stringResource(Res.string.collab_no_changes)
                        else stringResource(Res.string.collab_changes, changes.size),
                        style = MaterialTheme.typography.labelLarge,
                        color = c.onSurfaceVariant,
                    )
                    changes.take(40).forEach { ch -> ChangeLine(ch) }
                    if (changes.size > 40) {
                        Text(stringResource(Res.string.collab_more_changes, changes.size - 40), style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (verified) {
                            OutlinedButton(onClick = { showInvite = true }, enabled = !busy) {
                                Text(stringResource(Res.string.collab_invite))
                            }
                        }
                        OutlinedButton(onClick = { showHistory = !showHistory }) {
                            Text(stringResource(Res.string.collab_history))
                        }
                    }
                    if (showHistory) {
                        if (commits.isEmpty()) {
                            Text(stringResource(Res.string.collab_no_commits), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                        }
                        commits.forEach { commit ->
                            TextButton(onClick = { openDiff(commit) }, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(commit.message, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        stringResource(Res.string.collab_by, commit.authorName) + " · " + commit.createdAt,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = c.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (invites.isNotEmpty()) {
            item("invites") {
                CollabCard {
                    Text(stringResource(Res.string.collab_invites_title), style = MaterialTheme.typography.titleMedium)
                    invites.forEach { inv ->
                        Text(inv.projectName, style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(Res.string.collab_invited_by, inv.fromName),
                            style = MaterialTheme.typography.bodySmall,
                            color = c.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { act(acceptedText) { acceptShared(inv) } }, enabled = !busy) {
                                Text(stringResource(Res.string.collab_accept))
                            }
                            OutlinedButton(onClick = { act(declinedText) { backend.store.collabRespond(inv.id, false) } }, enabled = !busy) {
                                Text(stringResource(Res.string.collab_decline))
                            }
                        }
                    }
                }
            }
        }

        if (!linked && projects.isNotEmpty()) {
            item("mine") {
                CollabCard {
                    Text(stringResource(Res.string.collab_my_shared), style = MaterialTheme.typography.titleMedium)
                    projects.forEach { p ->
                        Text(p.name, style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(
                            onClick = { act(joinedText) { backend.store.collabJoin(project.rootPath, p.id) } },
                            enabled = !busy,
                        ) { Text(stringResource(Res.string.collab_use_here)) }
                    }
                }
            }
        }

        notice?.let { text ->
            item("notice") {
                Text(text, style = MaterialTheme.typography.bodyMedium, color = if (noticeIsError) c.error else c.primary)
            }
        }
        if (busy) {
            item("busy") { Text(stringResource(Res.string.collab_working), style = MaterialTheme.typography.bodySmall) }
        }
    }

    if (showInvite && verified && projectId != null) {
        InviteSheet(
            backend = backend,
            projectId = projectId,
            onDismiss = { showInvite = false },
            onDone = { err ->
                noticeIsError = err != null
                notice = err ?: invitedText
                reload++
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InviteSheet(
    backend: IdeBackend,
    projectId: String,
    onDismiss: () -> Unit,
    onDone: (String?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState()
    var candidates by remember(projectId) { mutableStateOf<List<UiCollabCandidate>>(emptyList()) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(projectId, tick) { candidates = backend.store.collabCandidates(projectId) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(Res.string.collab_candidates_title), style = MaterialTheme.typography.titleMedium)
            if (candidates.isEmpty()) {
                Text(
                    stringResource(Res.string.collab_candidates_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            candidates.forEach { cand ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(cand.displayName.ifBlank { cand.handle.orEmpty() }, style = MaterialTheme.typography.titleSmall)
                        cand.handle?.let { Text("@$it", style = MaterialTheme.typography.bodySmall) }
                    }
                    Button(onClick = {
                        scope.launch {
                            val err = backend.store.collabInvite(projectId, cand.id)
                            onDone(err)
                            tick++
                        }
                    }) { Text(stringResource(Res.string.collab_candidate_action)) }
                }
            }
        }
    }
}

@Composable
private fun CommitDiffView(commit: UiCollabCommit, diffs: List<UiCollabDiff>, onBack: () -> Unit) {
    val c = MaterialTheme.colorScheme
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
    ) {
        item("top") {
            TextButton(onClick = onBack) { Text(stringResource(Res.string.collab_back)) }
            Text(commit.message, style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(Res.string.collab_by, commit.authorName) + " · " + commit.createdAt,
                style = MaterialTheme.typography.bodySmall,
                color = c.onSurfaceVariant,
            )
        }
        if (diffs.isEmpty()) {
            item("none") { Text(stringResource(Res.string.collab_no_files), style = MaterialTheme.typography.bodyMedium) }
        }
        items(diffs, key = { it.path }) { d -> DiffFileCard(d) }
    }
}

@Composable
private fun DiffFileCard(d: UiCollabDiff) {
    val c = MaterialTheme.colorScheme
    CollabCard {
        Text(d.path, style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace)
        Text(changeLabel(d.change), style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
        d.before?.let { CodeBlock(stringResource(Res.string.collab_before), it, c.errorContainer, c.onErrorContainer) }
        d.after?.let { CodeBlock(stringResource(Res.string.collab_after), it, c.primaryContainer, c.onPrimaryContainer) }
    }
}

@Composable
private fun CodeBlock(label: String, text: String, container: Color, content: Color) {
    Text(label, style = MaterialTheme.typography.labelSmall)
    Surface(color = container, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            text.lines().take(MAX_DIFF_LINES).joinToString("\n"),
            color = content,
            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
            modifier = Modifier.padding(10.dp),
        )
    }
}

@Composable
private fun ChangeLine(ch: UiCollabChange) {
    Text(
        changeLabel(ch.change) + "  " + ch.path,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
    )
}

@Composable
private fun changeLabel(change: String): String = when (change) {
    "added" -> stringResource(Res.string.collab_change_added)
    "deleted" -> stringResource(Res.string.collab_change_deleted)
    else -> stringResource(Res.string.collab_change_modified)
}

@Composable
private fun CollabCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}
