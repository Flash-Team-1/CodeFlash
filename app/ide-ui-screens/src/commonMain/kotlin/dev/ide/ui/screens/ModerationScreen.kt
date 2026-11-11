package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiAdminPaymentRequest
import dev.ide.ui.backend.UiAdminPlan
import dev.ide.ui.backend.UiAdminFeature
import dev.ide.ui.backend.UiAdminRelease
import dev.ide.ui.backend.UiListingEdit
import dev.ide.ui.backend.UiManagedPublisher
import dev.ide.ui.backend.UiModerationQueue
import dev.ide.ui.backend.UiPendingSubmission
import dev.ide.ui.backend.UiPublisherPage
import dev.ide.ui.backend.UiRenewalRequest
import dev.ide.ui.backend.UiReportedContent
import dev.ide.ui.components.Chip
import dev.ide.ui.components.Eyebrow
import dev.ide.ui.components.LinearProgressIndicator
import dev.ide.ui.components.PillChip
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.learn_hidden_label
import dev.ide.ui.generated.resources.learn_hide
import dev.ide.ui.generated.resources.learn_keep
import dev.ide.ui.generated.resources.learn_reports_n
import dev.ide.ui.generated.resources.learn_reports_title
import dev.ide.ui.generated.resources.*
import dev.ide.ui.icons.CaSymbols
import dev.ide.ui.itemsKeyed
import dev.ide.ui.theme.Symbol
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Reviewing what people have published to the store, from inside the IDE.
 *
 * It exists because moderating meant opening a separate page with the project's master key pasted into it.
 * That key bypasses row-level security completely, so it could not be carried anywhere; now a moderator is
 * an ordinary signed-in account whose uuid is in `store_admins`, and the same session that publishes a
 * project is the one that reviews someone else's.
 *
 * **Nothing here is what permits anything.** Every call behind this screen is re-checked against
 * `store_admins` by the database and refused for anyone else, so the reason the entry point is hidden from
 * everybody else is that a page of buttons that all fail is worse than no page — not that hiding it is the
 * protection.
 *
 * What a decision actually costs is worth stating, because the screen deliberately does not soften it:
 * approving copies the archive into the public bucket, publishes the listing with whatever text the
 * version proposed, and pushes "your project is live" to its publisher. Rejecting sends them the note and
 * nothing else, which is why the note is required and why the field is in front of the button rather than
 * behind a confirmation.
 */
@Composable
fun ModerationScreen(
    backend: IdeBackend,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Opens a published listing by its slug, for a report about one. */
    onOpenItem: (String) -> Unit = {},
    /** Opens a publisher's public page by handle. */
    onOpenPublisher: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(ModerationTab.Queue) }
    var epoch by remember { mutableStateOf(0) }
    var queue by remember { mutableStateOf(UiModerationQueue(loading = true)) }
    var reports by remember { mutableStateOf<List<UiReportedContent>>(emptyList()) }
    var learnReports by remember { mutableStateOf<List<dev.ide.ui.backend.UiLearnReport>>(emptyList()) }
    var publishers by remember { mutableStateOf(UiPublisherPage()) }
    var renewals by remember { mutableStateOf<List<UiRenewalRequest>>(emptyList()) }
    var payments by remember { mutableStateOf<List<UiAdminPaymentRequest>>(emptyList()) }
    var plans by remember { mutableStateOf<List<UiAdminPlan>>(emptyList()) }
    var features by remember { mutableStateOf<List<UiAdminFeature>>(emptyList()) }
    var releases by remember { mutableStateOf<List<UiAdminRelease>>(emptyList()) }
    // What the last decision said. Kept as the backend wrote it: "was already approved" is what a second
    // moderator sees, and paraphrasing it would lose the only part that explains anything.
    var notice by remember { mutableStateOf<String?>(null) }
    // The submission a Reject tap is composing a note for. A dialog rather than an inline field because
    // the note is the whole of what its publisher receives, and it should be written deliberately.
    var rejecting by remember { mutableStateOf<UiPendingSubmission?>(null) }
    // Set when an approval needs the one question the engine cannot answer for itself.
    var iconQuestion by remember { mutableStateOf<UiPendingSubmission?>(null) }
    var working by remember { mutableStateOf(false) }
    // Not a permission check — the backend re-checks every call against `store_admins` and refuses. It is
    // here because this route is reachable BY NAME: a notification target is a route string, so the screen
    // can be opened without going past the entry point that is hidden from everyone else. Without it, a
    // non-moderator who followed such a link would get a page whose every panel says "not a moderator".
    val allowed = remember { backend.store.moderationAvailable() && backend.store.isModerator() }

    LaunchedEffect(epoch) {
        if (!allowed) return@LaunchedEffect
        queue = queue.copy(loading = true, error = null)
        queue = runCatching { backend.store.reviewQueue() }
            .getOrElse { UiModerationQueue(error = it.message ?: "The review queue could not be read") }
        reports = runCatching { backend.store.openReports() }.getOrDefault(emptyList())
        learnReports = runCatching { backend.learn.reportQueue() }.getOrNull().orEmpty()
        publishers = runCatching { backend.store.managedPublishers() }
            .getOrElse { UiPublisherPage(error = it.message ?: "The publishers could not be read") }
        renewals = runCatching { backend.store.renewalRequests() }.getOrDefault(emptyList())
        payments = runCatching { backend.store.paymentRequestsForReview() }.getOrDefault(emptyList())
        plans = runCatching { backend.store.adminPlans() }.getOrDefault(emptyList())
        features = runCatching { backend.store.adminFeatures() }.getOrDefault(emptyList())
        releases = runCatching { backend.store.adminReleases() }.getOrDefault(emptyList())
    }

    /** Run a decision, show whatever it said, and reload either way: the queue moved regardless. */
    fun decide(action: suspend () -> String?) {
        if (working) return
        working = true
        scope.launch {
            notice = runCatching { action() }.getOrElse { it.message ?: "That did not go through" }
            working = false
            epoch++
        }
    }

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            DetailTopBar(
                title = stringResource(Res.string.moderation_title),
                isSaved = false,
                onBack = onBack,
            )
            if (!allowed) {
                NotAModerator()
                return@Column
            }
            Column(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
                ModerationTabs(tab, queue.pendingCount, reports.size, learnReports.size, renewals.size, payments.size) { tab = it }
                if (queue.loading || working) LinearProgressIndicator(Modifier.fillMaxWidth())
                notice?.let { Notice(it) { notice = null } }

                when (tab) {
                    ModerationTab.Queue -> QueueList(
                        backend = backend,
                        queue = queue,
                        enabled = !working,
                        onApprove = { submission ->
                            // A version that ships no icon while the listing HAS one is the only case with
                            // a real question in it, and only its publisher knows the answer, so it is
                            // asked rather than guessed. Everything else approves straight away.
                            if (submission.iconPath == null && submission.listing.iconPath != null) {
                                iconQuestion = submission
                            } else {
                                decide { backend.store.approveSubmission(submission.versionId) }
                            }
                        },
                        onReject = { rejecting = it },
                        onOpenPublisher = onOpenPublisher,
                        onReload = { epoch++ },
                    )

                    ModerationTab.Reports -> ReportList(
                        reports = reports,
                        enabled = !working,
                        onOpenItem = onOpenItem,
                        onHide = { report, hidden ->
                            val slug = report.itemSlug ?: return@ReportList
                            val author = report.reviewAuthorId ?: return@ReportList
                            decide { backend.store.setReviewHidden(slug, author, hidden) }
                        },
                        onResolve = { report, actioned ->
                            decide { backend.store.resolveReport(report.reportId, actioned) }
                        },
                    )

                    ModerationTab.Verify -> VerifyList(
                        backend = backend,
                        page = publishers,
                        enabled = !working,
                        onSet = { publisher, mode, days ->
                            decide { backend.store.setPublisherVerified(publisher.userId, mode, days) }
                        },
                    )

                    ModerationTab.Requests -> RenewalList(
                        requests = renewals,
                        enabled = !working,
                        onOpenPublisher = onOpenPublisher,
                        onDone = { id -> decide { backend.store.markRenewalHandled(id) } },
                        onVerify = { req ->
                            decide {
                                val failure = backend.store.setPublisherVerified(req.userId, "days", 30)
                                failure ?: backend.store.markRenewalHandled(req.id)
                            }
                        },
                    )

                    ModerationTab.Payments -> PaymentsList(
                        plans = plans,
                        payments = payments,
                        enabled = !working,
                        onConfirm = { id -> decide { backend.store.confirmPayment(id) } },
                        onReject = { id, note -> decide { backend.store.rejectPayment(id, note) } },
                        onSavePlan = { code, price, list, active ->
                            decide { backend.store.setPlan(code, price, list, active) }
                        },
                    )

                    ModerationTab.Features -> FeaturesList(
                        features = features,
                        enabled = !working,
                        onSave = { key, on, percent, audience, ids ->
                            decide { backend.store.setFeature(key, on, percent, audience, ids) }
                        },
                    )

                    ModerationTab.Updates -> ReleasesList(
                        releases = releases,
                        enabled = !working,
                        onPublish = { code, name, channel, url, notes, percent, audience, mandatory ->
                            decide { backend.store.publishRelease(code, name, channel, url, notes, percent, audience, mandatory) }
                        },
                        onEdit = { id, on, percent, audience, mandatory ->
                            decide { backend.store.setRelease(id, on, percent, audience, mandatory) }
                        },
                        onDelete = { id -> decide { backend.store.deleteRelease(id) } },
                    )

                    ModerationTab.Learn -> LearnReportList(
                        reports = learnReports,
                        enabled = !working,
                        onResolve = { report, hide -> decide { backend.learn.resolveReport(report.id, hide) } },
                    )
                }
            }
        }
    }

    rejecting?.let { target ->
        RejectDialog(
            submission = target,
            onDismiss = { rejecting = null },
            onReject = { note ->
                rejecting = null
                decide { backend.store.rejectSubmission(target.versionId, note) }
            },
        )
    }

    iconQuestion?.let { target ->
        AlertDialog(
            onDismissRequest = { iconQuestion = null },
            title = { Text(stringResource(Res.string.moderation_icon_title)) },
            text = {
                Text(stringResource(Res.string.moderation_icon_body, target.listing.title, target.version))
            },
            confirmButton = {
                TextButton(onClick = {
                    iconQuestion = null
                    decide { backend.store.approveSubmission(target.versionId, clearIconIfMissing = true) }
                }) { Text(stringResource(Res.string.moderation_icon_clear)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    iconQuestion = null
                    decide { backend.store.approveSubmission(target.versionId, clearIconIfMissing = false) }
                }) { Text(stringResource(Res.string.moderation_icon_keep)) }
            },
        )
    }
}

/** The publishers in the store, with a Verify button that asks for how long. */
@Composable
private fun VerifyList(
    backend: IdeBackend,
    page: UiPublisherPage,
    enabled: Boolean,
    onSet: (UiManagedPublisher, String, Int?) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    var choosing by remember { mutableStateOf<UiManagedPublisher?>(null) }
    val failure = page.error
    if (failure != null) {
        Text(
            failure,
            style = MaterialTheme.typography.bodyMedium,
            color = c.error,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        )
        return
    }
    if (page.publishers.isEmpty()) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 44.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Symbol(CaSymbols.checkCircle, contentDescription = null, size = 34.dp, tint = c.onSurfaceVariant)
            Text(
                stringResource(Res.string.verify_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = c.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        itemsKeyed(page.publishers, key = { it.userId }) { publisher ->
            val name = publisher.displayName.ifBlank { publisher.handle }
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = c.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
            ) {
                Row(
                    Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ProfileAvatar(backend = backend, name = name, avatarUrl = publisher.avatarUrl, size = 44.dp)
                    Column(Modifier.weight(1f)) {
                        Text(
                            name,
                            style = MaterialTheme.typography.titleSmall,
                            color = c.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "@${publisher.handle}",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(6.dp))
                        VerifyStatus(publisher)
                    }
                    OutlinedButton(onClick = { choosing = publisher }, enabled = enabled) {
                        Text(
                            stringResource(
                                if (publisher.verified) Res.string.verify_change else Res.string.verify_button,
                            ),
                        )
                    }
                }
            }
        }
    }

    choosing?.let { target ->
        VerifyDialog(
            publisher = target,
            onDismiss = { choosing = null },
            onPick = { mode, days ->
                choosing = null
                onSet(target, mode, days)
            },
        )
    }
}

/** Verified forever, verified until a date, or not verified. */
@Composable
private fun VerifyStatus(publisher: UiManagedPublisher) {
    val c = MaterialTheme.colorScheme
    val until = publisher.verifiedUntil
    val label = when {
        !publisher.verified -> stringResource(Res.string.verify_none)
        until == null -> stringResource(Res.string.verify_forever)
        else -> stringResource(Res.string.verify_until, until.take(10))
    }
    Chip(
        label,
        fill = if (publisher.verified) c.primaryContainer else c.surfaceContainerHigh,
        textColor = if (publisher.verified) c.onPrimaryContainer else c.onSurfaceVariant,
    )
}

/**
 * How long to verify for. Presets cover the usual cases; the field covers the rest, between 1 and 3650 days,
 * which is the same range the backend accepts.
 */
@Composable
private fun VerifyDialog(
    publisher: UiManagedPublisher,
    onDismiss: () -> Unit,
    onPick: (String, Int?) -> Unit,
) {
    var custom by remember { mutableStateOf("") }
    val customDays = custom.toIntOrNull()?.takeIf { it in 1..3650 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    Res.string.verify_dialog_title,
                    publisher.displayName.ifBlank { publisher.handle },
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onPick("days", 30) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.verify_one_month))
                }
                OutlinedButton(onClick = { onPick("days", 60) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.verify_two_months))
                }
                OutlinedButton(onClick = { onPick("forever", null) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.verify_forever_button))
                }
                if (publisher.verified) {
                    TextButton(onClick = { onPick("off", null) }) {
                        Text(stringResource(Res.string.verify_remove))
                    }
                }
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = custom,
                    onValueChange = { typed -> custom = typed.filter { it.isDigit() }.take(4) },
                    label = { Text(stringResource(Res.string.verify_custom_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { customDays?.let { onPick("days", it) } },
                enabled = customDays != null,
            ) { Text(stringResource(Res.string.verify_custom_button)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.verify_cancel)) }
        },
    )
}

/**
 * What someone who is not a moderator sees.
 *
 * Said plainly and once, rather than as four panels each reporting their own 403. Nothing here hints at
 * what is in the queue, because the person reading it is not entitled to know.
 */
@Composable
private fun NotAModerator() {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 44.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Symbol(
            CaSymbols.gavel,
            contentDescription = null,
            size = 32.dp,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(Res.string.moderation_not_allowed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private enum class ModerationTab { Queue, Reports, Learn, Verify, Requests, Payments, Features, Updates }

/**
 * Verification requests from publishers who asked for one. Tap a row for their details, then open their
 * profile, verify them for 30 days, or mark the request done without verifying.
 */
@Composable
private fun RenewalList(
    requests: List<UiRenewalRequest>,
    enabled: Boolean,
    onOpenPublisher: (String) -> Unit,
    onDone: (String) -> Unit,
    onVerify: (UiRenewalRequest) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    var open by remember { mutableStateOf<UiRenewalRequest?>(null) }
    if (requests.isEmpty()) {
        Text(
            stringResource(Res.string.requests_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = c.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        )
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(requests, key = { it.id }) { r ->
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = c.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth().clickable { open = r },
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "${r.displayName.ifBlank { r.handle }}  @${r.handle}",
                        style = MaterialTheme.typography.titleSmall,
                        color = c.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        r.note?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.requests_no_note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    open?.let { r ->
        val status = when {
            !r.verified -> stringResource(Res.string.requests_status_unverified)
            r.verifiedUntil == null -> stringResource(Res.string.requests_status_forever)
            else -> stringResource(Res.string.requests_status_verified_until, r.verifiedUntil.orEmpty().take(10))
        }
        AlertDialog(
            onDismissRequest = { open = null },
            title = { Text(r.displayName.ifBlank { r.handle }) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("@${r.handle}", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                    Text(status, style = MaterialTheme.typography.bodyMedium, color = c.onSurface)
                    Text(
                        stringResource(Res.string.requests_sent_at, r.createdAt.take(10)),
                        style = MaterialTheme.typography.bodySmall,
                        color = c.onSurfaceVariant,
                    )
                    Text(
                        r.note?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.requests_no_note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.onSurface,
                    )
                }
            },
            confirmButton = {
                Row {
                    TextButton(enabled = enabled, onClick = { open = null; onVerify(r) }) {
                        Text(stringResource(Res.string.requests_verify_month))
                    }
                    TextButton(enabled = enabled, onClick = { open = null; onDone(r.id) }) {
                        Text(stringResource(Res.string.requests_done))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { open = null; onOpenPublisher(r.handle) }) {
                    Text(stringResource(Res.string.requests_open_profile))
                }
            },
        )
    }
}

@Composable
private fun ModerationTabs(
    selected: ModerationTab,
    waiting: Int,
    reports: Int,
    learnReports: Int,
    requests: Int,
    payments: Int,
    onSelect: (ModerationTab) -> Unit,
) {
    LazyRow(
        Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsKeyed(ModerationTab.entries, key = { it.name }) { t ->
            // The count is in the label rather than on a badge: a moderator opening this wants to know
            // whether there is anything to do before deciding which tab to be on.
            val count = when (t) {
                ModerationTab.Queue -> waiting
                ModerationTab.Reports -> reports
                ModerationTab.Learn -> learnReports
                ModerationTab.Verify -> 0
                ModerationTab.Requests -> requests
                ModerationTab.Payments -> payments
                ModerationTab.Features, ModerationTab.Updates -> 0
            }
            val label = when (t) {
                ModerationTab.Queue -> stringResource(Res.string.moderation_tab_queue)
                ModerationTab.Reports -> stringResource(Res.string.moderation_tab_reports)
                ModerationTab.Learn -> stringResource(Res.string.learn_reports_title)
                ModerationTab.Verify -> stringResource(Res.string.moderation_tab_verify)
                ModerationTab.Requests -> stringResource(Res.string.moderation_tab_requests)
                ModerationTab.Payments -> stringResource(Res.string.moderation_tab_payments)
                ModerationTab.Features -> stringResource(Res.string.moderation_tab_features)
                ModerationTab.Updates -> stringResource(Res.string.moderation_tab_updates)
            }
            PillChip(
                label = if (count > 0) "$label ($count)" else label,
                selected = t == selected,
                onClick = { onSelect(t) },
                height = 38.dp,
            )
        }
    }
}

@Composable
private fun Notice(text: String, onDismiss: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = c.secondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Row(
            Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = c.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onDismiss) { Text(stringResource(Res.string.dismiss)) }
        }
    }
}

@Composable
private fun QueueList(
    backend: IdeBackend,
    queue: UiModerationQueue,
    enabled: Boolean,
    onApprove: (UiPendingSubmission) -> Unit,
    onReject: (UiPendingSubmission) -> Unit,
    onOpenPublisher: (String) -> Unit,
    onReload: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    queue.error?.let { reason ->
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(reason, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
            OutlinedButton(onReload) { Text(stringResource(Res.string.retry)) }
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        if (queue.pending.isEmpty() && !queue.loading) {
            item("clear") {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 44.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Symbol(CaSymbols.checkCircle, contentDescription = null, size = 34.dp, tint = c.primary)
                    Text(
                        stringResource(Res.string.moderation_queue_clear),
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.onSurfaceVariant,
                    )
                }
            }
        }
        itemsKeyed(queue.pending, key = { it.versionId }) { submission ->
            SubmissionCard(
                backend = backend,
                submission = submission,
                enabled = enabled,
                onApprove = { onApprove(submission) },
                onReject = { onReject(submission) },
                onOpenPublisher = onOpenPublisher,
            )
        }
        if (queue.recent.isNotEmpty()) {
            item("recent_head") {
                Spacer(Modifier.height(20.dp))
                Eyebrow(
                    stringResource(Res.string.moderation_recent),
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                Spacer(Modifier.height(6.dp))
            }
            itemsKeyed(queue.recent, key = { "done:" + it.versionId }) { submission ->
                DecidedRow(submission)
            }
        }
    }
}

/**
 * One submission, with everything a decision needs on the card.
 *
 * The file manifest is behind a disclosure and everything else is not, because the point of reviewing is
 * seeing what is in the archive and a reviewer who has to tap twice for the interesting part stops
 * looking. The screenshots are the submitter's, in the private bucket, and are fetched with the
 * moderator's own session.
 */
@Composable
private fun SubmissionCard(
    backend: IdeBackend,
    submission: UiPendingSubmission,
    enabled: Boolean,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onOpenPublisher: (String) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    var showFiles by remember(submission.versionId) { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = c.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(
                        submission.listing.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = c.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(Res.string.moderation_version_by, submission.version, submitterLabel(submission)),
                        style = MaterialTheme.typography.bodySmall,
                        color = c.onSurfaceVariant,
                    )
                }
                submission.submitter?.handle?.let { handle ->
                    TextButton(onClick = { onOpenPublisher(handle) }) {
                        Text(stringResource(Res.string.moderation_view_publisher))
                    }
                }
            }

            if (submission.listing.summary.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    submission.listing.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(10.dp))
            // FlowRow, not Row: a card whose publisher is banned carries one chip more than fits, and a
            // Row pushes the overflow off the edge rather than wrapping it. The snapshot caught "2.2 MB"
            // rendered one letter per line against the right margin.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // A banned publisher's pending upload still reaches the queue, and that is the one fact on
                // this card that changes the decision before anything else is read.
                if (submission.submitter?.banned == true) {
                    Chip(
                        stringResource(Res.string.moderation_flag_banned),
                        fill = c.errorContainer,
                        textColor = c.onErrorContainer,
                    )
                }
                Chip(submission.listing.category.ifBlank { "—" })
                submission.listing.language?.let { Chip(it) }
                Chip(stringResource(Res.string.moderation_files, submission.fileCount))
                Chip(moderationSize(submission.sizeBytes))
            }

            if (submission.screenshotPaths.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                SubmissionShots(backend, submission.screenshotPaths)
            }

            if (submission.edits.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                ListingEdits(submission.edits)
            }

            submission.changelog?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(12.dp))
                Eyebrow(stringResource(Res.string.moderation_changelog))
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
            }

            if (submission.files.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = { showFiles = !showFiles }) {
                    Text(
                        if (showFiles) {
                            stringResource(Res.string.moderation_hide_files)
                        } else {
                            stringResource(Res.string.moderation_show_files, submission.files.size)
                        },
                    )
                }
                if (showFiles) {
                    Column(Modifier.fillMaxWidth()) {
                        // Capped: a manifest can hold two thousand entries and a reviewer scrolling past
                        // all of them is not reviewing anything. The count above says how many there are.
                        submission.files.take(MANIFEST_SHOWN).forEach { file ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 1.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    file.path,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = c.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    moderationSize(file.sizeBytes),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = c.outline,
                                )
                            }
                        }
                        if (submission.files.size > MANIFEST_SHOWN) {
                            Text(
                                stringResource(
                                    Res.string.moderation_files_more,
                                    submission.files.size - MANIFEST_SHOWN,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = c.outline,
                                modifier = Modifier.padding(top = 3.dp),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onApprove, enabled = enabled, modifier = Modifier.weight(1f)) {
                    Text(stringResource(Res.string.moderation_approve))
                }
                OutlinedButton(onClick = onReject, enabled = enabled, modifier = Modifier.weight(1f)) {
                    Text(stringResource(Res.string.moderation_reject), color = c.error)
                }
            }
        }
    }
}

/** What a version proposes to change about its listing, with the live text beside it. */
@Composable
private fun ListingEdits(edits: List<UiListingEdit>) {
    val c = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(14.dp), color = c.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Eyebrow(stringResource(Res.string.moderation_listing_edit), color = c.onTertiaryContainer)
            edits.forEach { edit ->
                Spacer(Modifier.height(8.dp))
                Text(
                    edit.field.replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.labelSmall,
                    color = c.onTertiaryContainer,
                )
                Text(
                    edit.current.ifBlank { stringResource(Res.string.moderation_edit_nothing) },
                    style = MaterialTheme.typography.bodySmall,
                    color = c.onTertiaryContainer.copy(alpha = 0.6f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    edit.proposed.ifBlank { stringResource(Res.string.moderation_edit_nothing) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.onTertiaryContainer,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The submitted screenshots.
 *
 * These are in the PRIVATE uploads bucket, so they resolve through the moderation download rather than the
 * public media cache the store's own galleries use. That is the whole reason this is not `ShotImage` with
 * a different path: nothing anonymous can read them.
 */
@Composable
private fun SubmissionShots(backend: IdeBackend, paths: List<String>) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsKeyed(paths, key = { it }) { path ->
            val file by produceState<String?>(null, path) {
                value = runCatching { backend.store.submissionImageFile(path) }.getOrNull()
            }
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.width(150.dp).height(96.dp),
            ) {
                file?.let { ShotImage(backend, it, ContentScale.Crop, Modifier.fillMaxSize()) }
            }
        }
    }
}

/** A decision already taken. Read-only: the queue is where anything is acted on. */
@Composable
private fun DecidedRow(submission: UiPendingSubmission) {
    val c = MaterialTheme.colorScheme
    val approved = submission.status == "approved"
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Symbol(
            if (approved) CaSymbols.checkCircle else CaSymbols.block,
            contentDescription = null,
            size = 18.dp,
            tint = if (approved) c.primary else c.error,
        )
        Column(Modifier.weight(1f)) {
            Text(
                "${submission.listing.title} ${submission.version}",
                style = MaterialTheme.typography.bodyMedium,
                color = c.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            submission.reviewNote?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 2)
            }
        }
    }
}

@Composable
private fun ReportList(
    reports: List<UiReportedContent>,
    enabled: Boolean,
    onOpenItem: (String) -> Unit,
    onHide: (UiReportedContent, Boolean) -> Unit,
    onResolve: (UiReportedContent, Boolean) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    if (reports.isEmpty()) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 44.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Symbol(CaSymbols.checkCircle, contentDescription = null, size = 34.dp, tint = c.primary)
            Text(
                stringResource(Res.string.moderation_reports_none),
                style = MaterialTheme.typography.bodyMedium,
                color = c.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        itemsKeyed(reports, key = { it.reportId }) { report ->
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = c.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                report.itemTitle ?: report.itemSlug.orEmpty(),
                                style = MaterialTheme.typography.titleSmall,
                                color = c.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                if (report.isItemReport) {
                                    stringResource(Res.string.moderation_report_project)
                                } else {
                                    stringResource(Res.string.moderation_report_review)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = c.onSurfaceVariant,
                            )
                        }
                        Chip(report.reason, fill = c.errorContainer, textColor = c.onErrorContainer)
                    }
                    if (report.reviewHidden) {
                        Spacer(Modifier.height(6.dp))
                        Chip(stringResource(Res.string.moderation_already_hidden))
                    }
                    report.reviewText?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(10.dp))
                        Surface(shape = RoundedCornerShape(12.dp), color = c.surfaceContainerHighest) {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodyMedium,
                                color = c.onSurface,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            )
                        }
                    }
                    report.detail?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(Res.string.moderation_reporter_said, it),
                            style = MaterialTheme.typography.bodySmall,
                            color = c.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        report.itemSlug?.let { slug ->
                            TextButton(onClick = { onOpenItem(slug) }) {
                                Text(stringResource(Res.string.moderation_open_listing))
                            }
                        }
                        // Only for a review report: an item report has no review to hide, and the action
                        // for one is banning the publisher, which is not this screen.
                        if (!report.isItemReport && report.reviewAuthorId != null) {
                            TextButton(
                                onClick = { onHide(report, !report.reviewHidden) },
                                enabled = enabled,
                            ) {
                                Text(
                                    if (report.reviewHidden) {
                                        stringResource(Res.string.moderation_restore_review)
                                    } else {
                                        stringResource(Res.string.moderation_hide_review)
                                    },
                                )
                            }
                        }
                        TextButton(onClick = { onResolve(report, false) }, enabled = enabled) {
                            Text(stringResource(Res.string.moderation_dismiss_report))
                        }
                        TextButton(onClick = { onResolve(report, true) }, enabled = enabled) {
                            Text(stringResource(Res.string.moderation_mark_actioned))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Writing the rejection.
 *
 * The note is what the publisher receives and the only thing telling them what to change, so the button
 * stays disabled until there is one. The database refuses a blank note too; this is so the refusal
 * happens where the person can still do something about it.
 */
@Composable
private fun RejectDialog(
    submission: UiPendingSubmission,
    onDismiss: () -> Unit,
    onReject: (String) -> Unit,
) {
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.moderation_reject_title, submission.listing.title)) },
        text = {
            Column {
                Text(
                    stringResource(Res.string.moderation_reject_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(stringResource(Res.string.moderation_reject_note)) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onReject(note.trim()) }, enabled = note.isNotBlank()) {
                Text(stringResource(Res.string.moderation_reject), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
        },
    )
}

private const val MANIFEST_SHOWN = 40

private fun submitterLabel(submission: UiPendingSubmission): String =
    submission.submitter?.label ?: "unknown"

/** The same shape [StoreItemScreen] uses, so a size reads the same on both screens. */
private fun moderationSize(bytes: Long): String {
    val mb = bytes / 1_048_576.0
    if (mb >= 1.0) {
        val tenths = (mb * 10).toLong()
        return "${tenths / 10}.${tenths % 10} MB"
    }
    return "${(bytes / 1024).coerceAtLeast(1)} KB"
}

/**
 * Reported Learn content: one card per reported question, answer or solution, with how many reports it has
 * and why. Three open reports hide an item automatically, so "Keep" is also how an auto-hide is undone.
 */
@Composable
private fun LearnReportList(
    reports: List<dev.ide.ui.backend.UiLearnReport>,
    enabled: Boolean,
    onResolve: (dev.ide.ui.backend.UiLearnReport, hide: Boolean) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
        if (reports.isEmpty()) {
            item("clear") {
                Text(stringResource(Res.string.moderation_reports_none), color = c.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp))
            }
        }
        items(reports.size, key = { reports[it].id }) { i ->
            val r = reports[i]
            Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                color = c.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        listOfNotNull(r.kind, r.reason, stringResource(Res.string.learn_reports_n, r.reports),
                            if (r.hidden) stringResource(Res.string.learn_hidden_label) else null).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium, color = c.primary,
                    )
                    Text(r.excerpt, style = MaterialTheme.typography.bodyMedium, maxLines = 6)
                    r.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant) }
                    Text(
                        listOfNotNull(r.lessonTitle, r.authorHandle?.let { "@$it" }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = c.outline,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { onResolve(r, true) }, enabled = enabled) { Text(stringResource(Res.string.learn_hide)) }
                        TextButton(onClick = { onResolve(r, false) }, enabled = enabled) { Text(stringResource(Res.string.learn_keep)) }
                    }
                }
            }
        }
    }
}

/** A plan's name as the buyer sees it. */
@Composable
private fun planName(code: String): String = when (code) {
    "1m" -> stringResource(Res.string.payment_plan_month)
    "2m" -> stringResource(Res.string.payment_plan_two_months)
    else -> stringResource(Res.string.payment_plan_forever)
}

/**
 * The Payments tab: the plans and their offers on top (tap one to change its price or switch it off), then the
 * payments waiting for a decision. Confirming extends the buyer's verification; rejecting asks for a reason they will see.
 */
@Composable
private fun PaymentsList(
    plans: List<UiAdminPlan>,
    payments: List<UiAdminPaymentRequest>,
    enabled: Boolean,
    onConfirm: (String) -> Unit,
    onReject: (String, String?) -> Unit,
    onSavePlan: (String, Int, Int?, Boolean) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    var editing by remember { mutableStateOf<UiAdminPlan?>(null) }
    var rejecting by remember { mutableStateOf<UiAdminPaymentRequest?>(null) }
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "plans-title") {
            Text(
                stringResource(Res.string.payments_plan_prices_title),
                style = MaterialTheme.typography.titleMedium,
                color = c.onSurface,
            )
        }
        items(plans, key = { it.code }) { p ->
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = c.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = { editing = p }),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(planName(p.code), style = MaterialTheme.typography.titleSmall, color = c.onSurface)
                    Spacer(Modifier.height(4.dp))
                    val offer = p.listPriceEgp?.let { " · ${stringResource(Res.string.payment_offer_tag)} ($it)" }.orEmpty()
                    val state = if (p.active) stringResource(Res.string.payments_plan_on) else stringResource(Res.string.payments_plan_off)
                    Text(
                        "${p.priceEgp} ${stringResource(Res.string.payment_currency)}$offer · $state",
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.onSurfaceVariant,
                    )
                }
            }
        }
        item(key = "payments-title") {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(Res.string.moderation_tab_payments),
                style = MaterialTheme.typography.titleMedium,
                color = c.onSurface,
            )
        }
        if (payments.isEmpty()) {
            item(key = "payments-empty") {
                Text(
                    stringResource(Res.string.payments_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
        items(payments, key = { it.id }) { r ->
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = c.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "${r.displayName.ifBlank { r.handle }}  @${r.handle}",
                        style = MaterialTheme.typography.titleSmall,
                        color = c.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${planName(r.planCode)} · ${r.amountEgp} ${stringResource(Res.string.payment_currency)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.onSurface,
                    )
                    Text(
                        stringResource(Res.string.payments_ref, r.reference),
                        style = MaterialTheme.typography.bodySmall,
                        color = c.onSurfaceVariant,
                    )
                    Text(
                        stringResource(Res.string.requests_sent_at, r.createdAt.take(10)),
                        style = MaterialTheme.typography.bodySmall,
                        color = c.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row {
                        TextButton(enabled = enabled, onClick = { onConfirm(r.id) }) {
                            Text(stringResource(Res.string.payments_confirm))
                        }
                        TextButton(enabled = enabled, onClick = { rejecting = r }) {
                            Text(stringResource(Res.string.payments_reject))
                        }
                    }
                }
            }
        }
    }

    editing?.let { p ->
        PlanDialog(
            plan = p,
            onDismiss = { editing = null },
            onSave = { price, list, active ->
                editing = null
                onSavePlan(p.code, price, list, active)
            },
        )
    }
    rejecting?.let { r ->
        RejectPaymentDialog(
            request = r,
            onDismiss = { rejecting = null },
            onReject = { note ->
                rejecting = null
                onReject(r.id, note)
            },
        )
    }
}

/** Change one plan's price, or set an offer (a normal price above the offer; leave it empty for no offer), or switch it off. */
@Composable
private fun PlanDialog(
    plan: UiAdminPlan,
    onDismiss: () -> Unit,
    onSave: (Int, Int?, Boolean) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    var price by remember(plan.code) { mutableStateOf(plan.priceEgp.toString()) }
    var list by remember(plan.code) { mutableStateOf(plan.listPriceEgp?.toString().orEmpty()) }
    var active by remember(plan.code) { mutableStateOf(plan.active) }
    val priceValue = price.toIntOrNull()?.takeIf { it in 1..100_000 }
    val listValue = list.toIntOrNull()
    val listOk = list.isBlank() || (listValue != null && priceValue != null && listValue > priceValue)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(planName(plan.code)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = price,
                    onValueChange = { price = it.filter { ch -> ch.isDigit() }.take(6) },
                    label = { Text(stringResource(Res.string.payments_price_label)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = list,
                    onValueChange = { list = it.filter { ch -> ch.isDigit() }.take(6) },
                    label = { Text(stringResource(Res.string.payments_list_label)) },
                    singleLine = true,
                )
                TextButton(onClick = { active = !active }) {
                    Text(
                        if (active) stringResource(Res.string.payments_turn_off)
                        else stringResource(Res.string.payments_turn_on),
                    )
                }
                Text(
                    if (active) stringResource(Res.string.payments_plan_on) else stringResource(Res.string.payments_plan_off),
                    style = MaterialTheme.typography.bodySmall,
                    color = c.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = priceValue != null && listOk,
                onClick = { priceValue?.let { onSave(it, listValue, active) } },
            ) { Text(stringResource(Res.string.payments_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.payments_cancel)) }
        },
    )
}

/** The note a rejected buyer reads. Optional, but a reason is what tells them what to fix. */
@Composable
private fun RejectPaymentDialog(
    request: UiAdminPaymentRequest,
    onDismiss: () -> Unit,
    onReject: (String?) -> Unit,
) {
    var note by remember(request.id) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.payments_reject_title)) },
        text = {
            OutlinedTextField(
                value = note,
                onValueChange = { note = it.take(300) },
                label = { Text(stringResource(Res.string.payments_reject_note_label)) },
                minLines = 2,
            )
        },
        confirmButton = {
            TextButton(onClick = { onReject(note.trim().takeIf { it.isNotEmpty() }) }) {
                Text(stringResource(Res.string.payments_reject))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.payments_cancel)) }
        },
    )
}

private val featureKeyPattern = Regex("[a-z0-9-]{2,40}")

@Composable
private fun featureSummary(f: UiAdminFeature): String {
    val state = if (f.enabled) stringResource(Res.string.rollout_on) else stringResource(Res.string.rollout_off)
    val who = audienceLabel(f.audience)
    return "$state · ${f.percent}% · $who"
}

@Composable
private fun audienceLabel(audience: String): String =
    when (audience) {
        "verified" -> stringResource(Res.string.rollout_audience_verified)
        "admin" -> stringResource(Res.string.rollout_audience_admin)
        else -> stringResource(Res.string.rollout_audience_all)
    }

/** Features with their rollout. Tap one to change it, or add a new one with its key. */
@Composable
private fun FeaturesList(
    features: List<UiAdminFeature>,
    enabled: Boolean,
    onSave: (String, Boolean, Int, String, List<String>) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    var editing by remember { mutableStateOf<UiAdminFeature?>(null) }
    var creating by remember { mutableStateOf(false) }
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "add") {
            OutlinedButton(enabled = enabled, onClick = { creating = true }) {
                Text(stringResource(Res.string.rollout_add_feature))
            }
        }
        if (features.isEmpty()) {
            item(key = "empty") {
                Text(stringResource(Res.string.rollout_empty), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
            }
        }
        items(features, key = { it.key }) { f ->
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = c.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = { editing = f }),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(f.key, style = MaterialTheme.typography.titleSmall, color = c.onSurface)
                    Spacer(Modifier.height(4.dp))
                    Text(featureSummary(f), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                }
            }
        }
    }

    if (creating) {
        FeatureDialog(
            feature = null,
            onDismiss = { creating = false },
            onSave = { key, on, percent, audience, ids ->
                creating = false
                onSave(key, on, percent, audience, ids)
            },
        )
    }
    editing?.let { f ->
        FeatureDialog(
            feature = f,
            onDismiss = { editing = null },
            onSave = { key, on, percent, audience, ids ->
                editing = null
                onSave(key, on, percent, audience, ids)
            },
        )
    }
}

@Composable
private fun FeatureDialog(
    feature: UiAdminFeature?,
    onDismiss: () -> Unit,
    onSave: (String, Boolean, Int, String, List<String>) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    var key by remember(feature?.key) { mutableStateOf(feature?.key.orEmpty()) }
    var on by remember(feature?.key) { mutableStateOf(feature?.enabled ?: true) }
    var percent by remember(feature?.key) { mutableStateOf((feature?.percent ?: 100).toString()) }
    var audience by remember(feature?.key) { mutableStateOf(feature?.audience ?: "all") }
    val percentValue = percent.toIntOrNull()?.takeIf { it in 0..100 }
    val keyOk = feature != null || featureKeyPattern.matches(key)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(feature?.key ?: stringResource(Res.string.rollout_add_feature)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (feature == null) {
                    OutlinedTextField(
                        value = key,
                        onValueChange = { raw ->
                            key = raw.lowercase().filter { ch -> ch in 'a'..'z' || ch in '0'..'9' || ch == '-' }.take(40)
                        },
                        label = { Text(stringResource(Res.string.rollout_key_label)) },
                        singleLine = true,
                    )
                    if (key.isNotEmpty() && !keyOk) {
                        Text(
                            stringResource(Res.string.rollout_key_invalid),
                            style = MaterialTheme.typography.bodySmall,
                            color = c.error,
                        )
                    }
                }
                OutlinedTextField(
                    value = percent,
                    onValueChange = { percent = it.filter { ch -> ch.isDigit() }.take(3) },
                    label = { Text(stringResource(Res.string.rollout_percent_label)) },
                    singleLine = true,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(1, 5, 25, 50, 100).forEach { preset ->
                        TextButton(onClick = { percent = preset.toString() }) { Text("$preset%") }
                    }
                }
                TextButton(onClick = { on = !on }) {
                    Text(if (on) stringResource(Res.string.rollout_turn_off) else stringResource(Res.string.rollout_turn_on))
                }
                TextButton(onClick = { audience = if (audience == "all") "verified" else "all" }) {
                    Text(audienceLabel(audience))
                }
                if (feature != null) {
                    Text(
                        stringResource(Res.string.rollout_allowlist, feature.allowUserIds.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = c.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = keyOk && percentValue != null,
                onClick = {
                    val p = percentValue ?: return@TextButton
                    onSave(feature?.key ?: key, on, p, audience, feature?.allowUserIds.orEmpty())
                },
            ) { Text(stringResource(Res.string.rollout_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.rollout_cancel)) }
        },
    )
}

/** Builds you publish for the in-app update, with their channel, rollout and audience. */
@Composable
private fun ReleasesList(
    releases: List<UiAdminRelease>,
    enabled: Boolean,
    onPublish: (Int, String, String, String, String?, Int, String, Boolean) -> Unit,
    onEdit: (String, Boolean, Int, String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    var publishing by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<UiAdminRelease?>(null) }
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "publish") {
            OutlinedButton(enabled = enabled, onClick = { publishing = true }) {
                Text(stringResource(Res.string.release_publish))
            }
        }
        if (releases.isEmpty()) {
            item(key = "empty") {
                Text(stringResource(Res.string.release_empty), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
            }
        }
        items(releases, key = { it.id }) { r ->
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = c.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = { editing = r }),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        stringResource(Res.string.release_card, r.versionName, r.versionCode),
                        style = MaterialTheme.typography.titleSmall,
                        color = c.onSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                    val state = if (r.enabled) stringResource(Res.string.rollout_on) else stringResource(Res.string.rollout_off)
                    val required = if (r.mandatory) " · " + stringResource(Res.string.release_required_badge) else ""
                    Text(
                        "${channelLabel(r.channel)} · $state · ${r.percent}% · ${audienceLabel(r.audience)}$required",
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (publishing) {
        ReleaseDialog(
            release = null,
            onDismiss = { publishing = false },
            onPublish = { code, name, channel, url, notes, percent, audience, mandatory ->
                publishing = false
                onPublish(code, name, channel, url, notes, percent, audience, mandatory)
            },
            onEdit = { _, _, _, _, _ -> },
            onDelete = {},
        )
    }
    editing?.let { r ->
        ReleaseDialog(
            release = r,
            onDismiss = { editing = null },
            onPublish = { _, _, _, _, _, _, _, _ -> },
            onEdit = { id, on, percent, audience, mandatory ->
                editing = null
                onEdit(id, on, percent, audience, mandatory)
            },
            onDelete = { id ->
                editing = null
                onDelete(id)
            },
        )
    }
}

@Composable
private fun channelLabel(channel: String): String =
    if (channel == "beta") stringResource(Res.string.release_channel_beta)
    else stringResource(Res.string.release_channel_stable)

@Composable
private fun ReleaseDialog(
    release: UiAdminRelease?,
    onDismiss: () -> Unit,
    onPublish: (Int, String, String, String, String?, Int, String, Boolean) -> Unit,
    onEdit: (String, Boolean, Int, String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    var code by remember(release?.id) { mutableStateOf("") }
    var name by remember(release?.id) { mutableStateOf("") }
    var url by remember(release?.id) { mutableStateOf("") }
    var notes by remember(release?.id) { mutableStateOf("") }
    var channel by remember(release?.id) { mutableStateOf("stable") }
    var on by remember(release?.id) { mutableStateOf(release?.enabled ?: true) }
    var mandatory by remember(release?.id) { mutableStateOf(release?.mandatory ?: false) }
    var armedDelete by remember(release?.id) { mutableStateOf(false) }
    var percent by remember(release?.id) { mutableStateOf((release?.percent ?: 100).toString()) }
    var audience by remember(release?.id) { mutableStateOf(release?.audience ?: "all") }
    val percentValue = percent.toIntOrNull()?.takeIf { it in 0..100 }
    val codeValue = code.toIntOrNull()?.takeIf { it > 0 }
    val publishOk = codeValue != null && name.isNotBlank() && url.trim().startsWith("https://")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (release == null) stringResource(Res.string.release_publish)
                else stringResource(Res.string.release_card, release.versionName, release.versionCode),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (release == null) {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.filter { ch -> ch.isDigit() }.take(9) },
                        label = { Text(stringResource(Res.string.release_version_code)) },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(40) },
                        label = { Text(stringResource(Res.string.release_version_name)) },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it.trim() },
                        label = { Text(stringResource(Res.string.release_url)) },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it.take(1000) },
                        label = { Text(stringResource(Res.string.release_notes)) },
                        minLines = 2,
                    )
                    TextButton(onClick = { channel = if (channel == "stable") "beta" else "stable" }) {
                        Text("${stringResource(Res.string.release_channel_label)}: ${channelLabel(channel)}")
                    }
                } else {
                    TextButton(onClick = { on = !on }) {
                        Text(if (on) stringResource(Res.string.rollout_turn_off) else stringResource(Res.string.rollout_turn_on))
                    }
                }
                TextButton(onClick = { mandatory = !mandatory }) {
                    Text(if (mandatory) stringResource(Res.string.release_mandatory_on) else stringResource(Res.string.release_mandatory_off))
                }
                OutlinedTextField(
                    value = percent,
                    onValueChange = { percent = it.filter { ch -> ch.isDigit() }.take(3) },
                    label = { Text(stringResource(Res.string.rollout_percent_label)) },
                    singleLine = true,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(1, 5, 25, 50, 100).forEach { preset ->
                        TextButton(onClick = { percent = preset.toString() }) { Text("$preset%") }
                    }
                }
                TextButton(
                    onClick = {
                        // Cycles Everyone -> Verified only -> Admins only -> Everyone.
                        audience = when (audience) { "all" -> "verified"; "verified" -> "admin"; else -> "all" }
                    },
                ) {
                    Text(audienceLabel(audience))
                }
                if (release != null) {
                    TextButton(
                        onClick = {
                            // Two taps: the first arms it, the second removes the build.
                            if (armedDelete) onDelete(release.id) else armedDelete = true
                        },
                    ) {
                        Text(
                            if (armedDelete) stringResource(Res.string.release_delete_confirm) else stringResource(Res.string.release_delete),
                            color = c.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = percentValue != null && (release != null || publishOk),
                onClick = {
                    val p = percentValue ?: return@TextButton
                    if (release == null) {
                        val version = codeValue ?: return@TextButton
                        onPublish(version, name.trim(), channel, url.trim(), notes.trim().takeIf { it.isNotEmpty() }, p, audience, mandatory)
                    } else {
                        onEdit(release.id, on, p, audience, mandatory)
                    }
                },
            ) { Text(stringResource(Res.string.rollout_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.rollout_cancel)) }
        },
    )
}
