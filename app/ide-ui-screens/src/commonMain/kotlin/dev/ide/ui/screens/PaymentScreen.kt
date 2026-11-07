package dev.ide.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiPaymentRequest
import dev.ide.ui.backend.UiVerificationPlan
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.payment_copied
import dev.ide.ui.generated.resources.payment_copy
import dev.ide.ui.generated.resources.payment_currency
import dev.ide.ui.generated.resources.payment_empty_history
import dev.ide.ui.generated.resources.payment_history
import dev.ide.ui.generated.resources.payment_pay_with
import dev.ide.ui.generated.resources.payment_pending_block
import dev.ide.ui.generated.resources.payment_plan_forever
import dev.ide.ui.generated.resources.payment_plan_month
import dev.ide.ui.generated.resources.payment_plan_two_months
import dev.ide.ui.generated.resources.payment_ref_label
import dev.ide.ui.generated.resources.payment_sent
import dev.ide.ui.generated.resources.payment_status_confirmed
import dev.ide.ui.generated.resources.payment_status_pending
import dev.ide.ui.generated.resources.payment_status_rejected
import dev.ide.ui.generated.resources.payment_steps
import dev.ide.ui.generated.resources.payment_submit
import dev.ide.ui.generated.resources.payment_title
import dev.ide.ui.generated.resources.payment_offer_tag
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** The Vodafone Cash number every payment goes to. Change it here if the number changes. */
internal const val VODAFONE_CASH_NUMBER = "01067668406"

/**
 * Buying verification: pick a plan, send the money to the Vodafone Cash number, then paste the transfer
 * reference here. An admin checks the transfer and confirms it, and the status shows in the list below.
 */
@Composable
fun PaymentScreen(
    backend: IdeBackend,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    var plans by remember { mutableStateOf<List<UiVerificationPlan>>(emptyList()) }
    var history by remember { mutableStateOf<List<UiPaymentRequest>>(emptyList()) }
    var chosen by remember { mutableStateOf<String?>(null) }
    var reference by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    var sentOk by remember { mutableStateOf(false) }
    var epoch by remember { mutableStateOf(0) }

    LaunchedEffect(epoch) {
        plans = runCatching { backend.store.verificationPlans() }.getOrDefault(emptyList())
        history = runCatching { backend.store.myPaymentRequests() }.getOrDefault(emptyList())
        if (chosen == null) chosen = plans.firstOrNull()?.code
    }

    val waiting = history.any { it.status == "pending" }

    Column(modifier.fillMaxSize()) {
        DetailTopBar(title = stringResource(Res.string.payment_title), isSaved = false, onBack = onBack)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(plans, key = { it.code }) { plan ->
                val selected = chosen == plan.code
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (selected) c.primaryContainer else c.surfaceContainer,
                    modifier = Modifier.fillMaxWidth().clickable { chosen = plan.code },
                ) {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                planLabel(plan.code),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = if (selected) c.onPrimaryContainer else c.onSurface,
                            )
                            if (plan.listPriceEgp != null) {
                                Text(
                                    stringResource(Res.string.payment_offer_tag),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = c.primary,
                                )
                            }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            if (plan.listPriceEgp != null) {
                                Text(
                                    "${plan.listPriceEgp} ${stringResource(Res.string.payment_currency)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = c.onSurfaceVariant,
                                    textDecoration = TextDecoration.LineThrough,
                                )
                            }
                            Text(
                                "${plan.priceEgp} ${stringResource(Res.string.payment_currency)}",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                color = if (selected) c.onPrimaryContainer else c.onSurface,
                            )
                        }
                    }
                }
            }

            item("number") {
                Surface(shape = RoundedCornerShape(24.dp), color = c.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp)) {
                        Text(
                            stringResource(Res.string.payment_pay_with),
                            style = MaterialTheme.typography.bodyMedium,
                            color = c.onSecondaryContainer,
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                VODAFONE_CASH_NUMBER,
                                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                                color = c.onSecondaryContainer,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = {
                                clipboard.setText(AnnotatedString(VODAFONE_CASH_NUMBER))
                                copied = true
                            }) {
                                Text(
                                    if (copied) stringResource(Res.string.payment_copied) else stringResource(Res.string.payment_copy),
                                    color = c.onSecondaryContainer,
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(Res.string.payment_steps),
                            style = MaterialTheme.typography.bodySmall,
                            color = c.onSecondaryContainer,
                        )
                    }
                }
            }

            item("form") {
                OutlinedTextField(
                    value = reference,
                    onValueChange = { reference = it.uppercase().filter { ch -> ch.isLetterOrDigit() || ch == '-' }.take(40) },
                    label = { Text(stringResource(Res.string.payment_ref_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    enabled = !sending && !waiting && chosen != null && reference.length >= 4,
                    onClick = {
                        val plan = chosen ?: return@Button
                        sending = true
                        scope.launch {
                            val failure = backend.store.requestVerificationPayment(plan, reference)
                            sending = false
                            sentOk = failure == null
                            if (failure == null) reference = ""
                            message = failure
                            epoch++
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(Res.string.payment_submit))
                }
                if (waiting) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(Res.string.payment_pending_block),
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.onSurfaceVariant,
                    )
                }
                message?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = c.error)
                }
                if (sentOk) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(Res.string.payment_sent), style = MaterialTheme.typography.bodyMedium, color = c.primary)
                }
            }

            item("history_head") {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(Res.string.payment_history),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = c.onSurface,
                )
            }
            if (history.isEmpty()) {
                item("history_empty") {
                    Text(
                        stringResource(Res.string.payment_empty_history),
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.onSurfaceVariant,
                    )
                }
            }
            items(history, key = { it.id }) { p ->
                Surface(shape = RoundedCornerShape(18.dp), color = c.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            "${planLabel(p.planCode)}  ·  ${p.amountEgp} ${stringResource(Res.string.payment_currency)}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = c.onSurface,
                        )
                        Text(
                            statusLabel(p.status),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (p.status == "rejected") c.error else c.onSurfaceVariant,
                        )
                        p.adminNote?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                        }
                    }
                }
            }
            item("bottom") { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun planLabel(code: String): String = when (code) {
    "1m" -> stringResource(Res.string.payment_plan_month)
    "2m" -> stringResource(Res.string.payment_plan_two_months)
    else -> stringResource(Res.string.payment_plan_forever)
}

@Composable
private fun statusLabel(status: String): String = when (status) {
    "confirmed" -> stringResource(Res.string.payment_status_confirmed)
    "rejected" -> stringResource(Res.string.payment_status_rejected)
    else -> stringResource(Res.string.payment_status_pending)
}
