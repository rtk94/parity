package com.rknepp.parity.relationships.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rknepp.parity.app.LocalServiceLocator
import com.rknepp.parity.ui.components.ConfirmActionDialog
import com.rknepp.parity.ui.components.ErrorState
import com.rknepp.parity.ui.components.LoadingState
import com.rknepp.parity.ui.theme.ParityMoney
import com.rknepp.parity.ui.theme.ParityThemeDefaults
import com.rknepp.parity.ui.theme.PillShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Destructive/consequential actions gated behind a confirm dialog. */
private sealed interface PendingAction {
    data object RejectInvite : PendingAction
    data class Discard(val row: LedgerRow) : PendingAction
    data class Reverse(val row: LedgerRow) : PendingAction
    data class DeleteAttachment(val expenseId: Long, val attachment: AttachmentRow) : PendingAction
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelationshipDetailScreen(
    relationshipId: Long,
    onBack: () -> Unit,
    onNavigateToCreateExpense: () -> Unit,
    onNavigateToCreatePayment: () -> Unit,
) {
    val locator = LocalServiceLocator.current
    val vm: RelationshipDetailViewModel = viewModel(
        factory = RelationshipDetailViewModel.factory(locator, relationshipId),
    )
    val state by vm.state.collectAsState()
    val actionError by vm.actionError.collectAsState()
    val pendingOpen by vm.pendingOpen.collectAsState()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingAction by remember { mutableStateOf<PendingAction?>(null) }

    // The expense a picked file will attach to. Set just before launching
    // the picker; consumed when the picker returns.
    var uploadTargetExpenseId by remember { mutableStateOf<Long?>(null) }
    val pickFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val target = uploadTargetExpenseId
        uploadTargetExpenseId = null
        if (uri != null && target != null) {
            scope.launch {
                val picked = readPickedFile(context, uri)
                if (picked == null) {
                    snackbarHostState.showSnackbar("Could not read that file.")
                } else {
                    vm.uploadAttachment(target, picked.filename, picked.contentType, picked.bytes)
                }
            }
        }
    }

    // A downloaded attachment: write it to cache and hand it to the
    // system viewer, then clear the one-shot.
    LaunchedEffect(pendingOpen) {
        val file = pendingOpen ?: return@LaunchedEffect
        val uri = withContext(Dispatchers.IO) {
            runCatching { writeToCache(context, file.filename, file.bytes) }.getOrNull()
        }
        val opened = uri != null && runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, file.contentType)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
            )
        }.isSuccess
        if (!opened) {
            snackbarHostState.showSnackbar("No app can open ${file.filename}.")
        }
        vm.consumePendingOpen()
    }

    // Revalidate when returning from the create-expense / create-
    // payment screens so new entries appear without a manual reload.
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }

    LaunchedEffect(actionError) {
        actionError?.let { message ->
            snackbarHostState.showSnackbar(message)
            vm.consumeActionError()
        }
    }

    val loaded = state as? RelationshipDetailState.Loaded

    pendingAction?.let { action ->
        when (action) {
            PendingAction.RejectInvite -> ConfirmActionDialog(
                title = if (loaded?.data?.canAccept == true) "Decline invitation?" else "Cancel invite?",
                text = "This ends the pending relationship. Any pending entries " +
                    "bundled with the invite are discarded.",
                confirmLabel = if (loaded?.data?.canAccept == true) "Decline" else "Cancel invite",
                destructive = true,
                onConfirm = {
                    vm.reject()
                    pendingAction = null
                },
                onDismiss = { pendingAction = null },
            )
            is PendingAction.Discard -> ConfirmActionDialog(
                title = "Discard this ${action.row.type}?",
                text = "“${action.row.description}” will be removed from the " +
                    "pending ledger. This cannot be undone.",
                confirmLabel = "Discard",
                destructive = true,
                onConfirm = {
                    if (action.row.type == "expense") {
                        vm.discardExpense(action.row.id)
                    } else {
                        vm.discardPayment(action.row.id)
                    }
                    pendingAction = null
                },
                onDismiss = { pendingAction = null },
            )
            is PendingAction.Reverse -> ConfirmActionDialog(
                title = "Reverse this ${action.row.type}?",
                text = "Confirmed entries are never edited or deleted. Reversing " +
                    "creates an offsetting entry of ${action.row.amountText} that your " +
                    "counterparty must confirm.",
                confirmLabel = "Reverse",
                onConfirm = {
                    if (action.row.type == "expense") {
                        vm.reverseExpense(action.row.id)
                    } else {
                        vm.reversePayment(action.row.id)
                    }
                    pendingAction = null
                },
                onDismiss = { pendingAction = null },
            )
            is PendingAction.DeleteAttachment -> ConfirmActionDialog(
                title = "Delete this attachment?",
                text = "“${action.attachment.filename}” will be permanently removed.",
                confirmLabel = "Delete",
                destructive = true,
                onConfirm = {
                    vm.deleteAttachment(action.expenseId, action.attachment.id)
                    pendingAction = null
                },
                onDismiss = { pendingAction = null },
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(loaded?.data?.counterpartyName ?: "Relationship")
                        loaded?.data?.counterpartyUsername?.let {
                            Text(
                                "@$it",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (val s = state) {
                RelationshipDetailState.Loading -> LoadingState()
                RelationshipDetailState.Error -> ErrorState(
                    message = "Couldn't load this relationship.",
                    onRetry = { vm.reload() },
                )
                is RelationshipDetailState.Loaded -> {
                    RelationshipDetailContent(
                        data = s.data,
                        onAccept = { vm.accept() },
                        onReject = { pendingAction = PendingAction.RejectInvite },
                        onAddExpense = onNavigateToCreateExpense,
                        onAddPayment = onNavigateToCreatePayment,
                        onConfirm = { row ->
                            if (row.type == "expense") vm.confirmExpense(row.id)
                            else vm.confirmPayment(row.id)
                        },
                        onDiscard = { row -> pendingAction = PendingAction.Discard(row) },
                        onReverse = { row -> pendingAction = PendingAction.Reverse(row) },
                        onToggleComments = { row -> vm.toggleComments(row.id, row.type) },
                        onCommentDraftChange = { row, draft ->
                            vm.updateCommentDraft(row.id, row.type, draft)
                        },
                        onPostComment = { row -> vm.postComment(row.id, row.type) },
                        onToggleAttachments = { row -> vm.toggleAttachments(row.id) },
                        onAddAttachment = { row ->
                            uploadTargetExpenseId = row.id
                            pickFileLauncher.launch(arrayOf("image/*", "application/pdf"))
                        },
                        onOpenAttachment = { row, att -> vm.openAttachment(row.id, att) },
                        onDeleteAttachment = { row, att ->
                            pendingAction = PendingAction.DeleteAttachment(row.id, att)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun RelationshipDetailContent(
    data: RelationshipDetailData,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onAddExpense: () -> Unit,
    onAddPayment: () -> Unit,
    onConfirm: (LedgerRow) -> Unit,
    onDiscard: (LedgerRow) -> Unit,
    onReverse: (LedgerRow) -> Unit,
    onToggleComments: (LedgerRow) -> Unit,
    onCommentDraftChange: (LedgerRow, String) -> Unit,
    onPostComment: (LedgerRow) -> Unit,
    onToggleAttachments: (LedgerRow) -> Unit,
    onAddAttachment: (LedgerRow) -> Unit,
    onOpenAttachment: (LedgerRow, AttachmentRow) -> Unit,
    onDeleteAttachment: (LedgerRow, AttachmentRow) -> Unit,
) {
    val pending = data.ledgerItems.filter { it.status == "pending" }
    val history = data.ledgerItems.filter { it.status != "pending" }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        if (data.canAccept || data.canReject) {
            item { InviteBanner(data, onAccept, onReject, Modifier.padding(top = 12.dp)) }
        }

        item {
            BalanceBand(
                data = data,
                modifier = Modifier.padding(top = if (data.canAccept || data.canReject) 0.dp else 12.dp),
            )
        }

        if (data.status == "accepted") {
            item { ActionBar(onAddExpense = onAddExpense, onAddPayment = onAddPayment) }
        }

        if (data.ledgerItems.isEmpty()) {
            item {
                Text(
                    "No entries yet. Expenses and payments you add will show up here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (pending.isNotEmpty()) {
            item {
                LedgerGroup(
                    header = if (pending.size == 1) "1 pending · needs you"
                    else "${pending.size} pending · needs you",
                    headerColor = ParityThemeDefaults.colors.pending,
                    rows = pending,
                    onConfirm = onConfirm,
                    onDiscard = onDiscard,
                    onReverse = onReverse,
                    onToggleComments = onToggleComments,
                    onCommentDraftChange = onCommentDraftChange,
                    onPostComment = onPostComment,
                    onToggleAttachments = onToggleAttachments,
                    onAddAttachment = onAddAttachment,
                    onOpenAttachment = onOpenAttachment,
                    onDeleteAttachment = onDeleteAttachment,
                )
            }
        }

        if (history.isNotEmpty()) {
            item {
                LedgerGroup(
                    header = "History",
                    headerColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    rows = history,
                    onConfirm = onConfirm,
                    onDiscard = onDiscard,
                    onReverse = onReverse,
                    onToggleComments = onToggleComments,
                    onCommentDraftChange = onCommentDraftChange,
                    onPostComment = onPostComment,
                    onToggleAttachments = onToggleAttachments,
                    onAddAttachment = onAddAttachment,
                    onOpenAttachment = onOpenAttachment,
                    onDeleteAttachment = onDeleteAttachment,
                )
            }
        }

        item { Box(modifier = Modifier.padding(8.dp)) }
    }
}

@Composable
private fun InviteBanner(
    data: RelationshipDetailData,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LabelCaps(
            if (data.canAccept) "invitation" else "awaiting acceptance",
            ParityThemeDefaults.colors.pending,
        )
        Text(
            text = if (data.canAccept) {
                "${data.counterpartyName} invited you to share a ${data.currencyCode} ledger."
            } else {
                "Waiting for ${data.counterpartyName} to accept your ${data.currencyCode} invite."
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            if (data.canAccept) {
                TextAction("Accept", MaterialTheme.colorScheme.tertiary, bold = true, onClick = onAccept)
            }
            if (data.canReject) {
                TextAction(
                    if (data.canAccept) "Decline" else "Cancel invite",
                    MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = onReject,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun BalanceBand(data: RelationshipDetailData, modifier: Modifier = Modifier) {
    val confirmed = data.confirmed
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LabelCaps("Balance", MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            confirmed.amountText,
            style = ParityMoney.screen,
            color = when {
                confirmed.settled -> MaterialTheme.colorScheme.onSurface
                confirmed.youOwe -> MaterialTheme.colorScheme.error
                else -> ParityThemeDefaults.colors.positive
            },
        )
        Text(
            when {
                confirmed.settled -> "all settled up"
                confirmed.youOwe -> "you owe ${confirmed.counterpartyName}"
                else -> "${confirmed.counterpartyName} owes you"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (data.projected != confirmed) {
            val projected = data.projected
            val projectedText = when {
                projected.settled -> "settled up"
                projected.youOwe -> "you owe ${projected.amountText}"
                else -> "${projected.counterpartyName} owes you ${projected.amountText}"
            }
            Text(
                "once pending confirms: $projectedText",
                style = MaterialTheme.typography.bodyMedium,
                color = ParityThemeDefaults.colors.pending,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun ActionBar(onAddExpense: () -> Unit, onAddPayment: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = onAddExpense,
            shape = PillShape,
            elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
        ) {
            Text("Add expense", style = MaterialTheme.typography.labelLarge)
        }
        TextAction(
            "Pay",
            MaterialTheme.colorScheme.tertiary,
            bold = true,
            onClick = onAddPayment,
            modifier = Modifier.padding(start = 20.dp),
        )
    }
}

@Composable
private fun LedgerGroup(
    header: String,
    headerColor: Color,
    rows: List<LedgerRow>,
    onConfirm: (LedgerRow) -> Unit,
    onDiscard: (LedgerRow) -> Unit,
    onReverse: (LedgerRow) -> Unit,
    onToggleComments: (LedgerRow) -> Unit,
    onCommentDraftChange: (LedgerRow, String) -> Unit,
    onPostComment: (LedgerRow) -> Unit,
    onToggleAttachments: (LedgerRow) -> Unit,
    onAddAttachment: (LedgerRow) -> Unit,
    onOpenAttachment: (LedgerRow, AttachmentRow) -> Unit,
    onDeleteAttachment: (LedgerRow, AttachmentRow) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        LabelCaps(header, headerColor)
        rows.forEachIndexed { index, item ->
            if (index > 0) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            LedgerEntry(
                item = item,
                onConfirm = { onConfirm(item) },
                onDiscard = { onDiscard(item) },
                onReverse = { onReverse(item) },
                onToggleComments = { onToggleComments(item) },
                onCommentDraftChange = { draft -> onCommentDraftChange(item, draft) },
                onPostComment = { onPostComment(item) },
                onToggleAttachments = { onToggleAttachments(item) },
                onAddAttachment = { onAddAttachment(item) },
                onOpenAttachment = { att -> onOpenAttachment(item, att) },
                onDeleteAttachment = { att -> onDeleteAttachment(item, att) },
            )
        }
    }
}

@Composable
private fun LedgerEntry(
    item: LedgerRow,
    onConfirm: () -> Unit,
    onDiscard: () -> Unit,
    onReverse: () -> Unit,
    onToggleComments: () -> Unit,
    onCommentDraftChange: (String) -> Unit,
    onPostComment: () -> Unit,
    onToggleAttachments: () -> Unit,
    onAddAttachment: () -> Unit,
    onOpenAttachment: (AttachmentRow) -> Unit,
    onDeleteAttachment: (AttachmentRow) -> Unit,
) {
    val inactive = item.status == "discarded"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (inactive) 0.55f else 1f)
            .padding(vertical = 13.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text(item.description, style = MaterialTheme.typography.titleMedium)
                Text(
                    item.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Row(modifier = Modifier.padding(top = 2.dp)) {
                    Text(
                        item.dateText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    item.category?.let {
                        Text(
                            " · $it",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (inactive) {
                        Text(
                            " · discarded",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            DeltaText(delta = item.deltaCents, amountText = item.amountText)
        }

        item.reversalItem?.let { ReversalLine(it) }

        val actions = buildList {
            if (item.canConfirm) add(Triple("Confirm", MaterialTheme.colorScheme.tertiary, onConfirm))
            if (item.canDiscard) add(
                Triple("Decline", MaterialTheme.colorScheme.onSurfaceVariant, onDiscard),
            )
            if (item.canReverse) add(
                Triple("Reverse", MaterialTheme.colorScheme.onSurfaceVariant, onReverse),
            )
            add(
                Triple(
                    if (item.comments != null) "Hide comments" else "Comments",
                    MaterialTheme.colorScheme.onSurfaceVariant,
                    onToggleComments,
                ),
            )
            // Attachments are receipts on expenses only.
            if (item.type == "expense") {
                add(
                    Triple(
                        if (item.attachments != null) "Hide files" else "Files",
                        MaterialTheme.colorScheme.onSurfaceVariant,
                        onToggleAttachments,
                    ),
                )
            }
        }
        Row(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            actions.forEach { (label, color, onClick) ->
                TextAction(label, color, bold = label == "Confirm", onClick = onClick)
            }
        }

        if (item.isLoadingComments) {
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .size(20.dp),
                strokeWidth = 2.dp,
            )
        } else if (item.comments != null) {
            CommentsSection(item = item, onDraftChange = onCommentDraftChange, onPost = onPostComment)
        }

        if (item.isLoadingAttachments) {
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .size(20.dp),
                strokeWidth = 2.dp,
            )
        } else if (item.attachments != null) {
            AttachmentsSection(
                item = item,
                onAdd = onAddAttachment,
                onOpen = onOpenAttachment,
                onDelete = onDeleteAttachment,
            )
        }
    }
}

/** A confirmed reversal, shown as an indented detail line under its original. */
@Composable
private fun ReversalLine(reversal: LedgerRow) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Reversed · ${reversal.dateText}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            "−${reversal.amountText}",
            style = ParityMoney.row,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Entry amount, colored by its effect on the caller: green when it
 * moves money toward you, red when away, muted when no balance effect.
 */
@Composable
private fun DeltaText(delta: Long?, amountText: String) {
    val color: Color = when {
        delta == null -> MaterialTheme.colorScheme.onSurfaceVariant
        delta > 0L -> ParityThemeDefaults.colors.positive
        delta < 0L -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Text(amountText, style = ParityMoney.row, color = color)
}

@Composable
private fun LabelCaps(text: String, color: Color) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = color)
}

@Composable
private fun TextAction(
    label: String,
    color: Color,
    bold: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium,
        color = color,
        modifier = modifier
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
    )
}

@Composable
private fun CommentsSection(
    item: LedgerRow,
    onDraftChange: (String) -> Unit,
    onPost: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    ) {
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        if (item.comments.isNullOrEmpty()) {
            Text(
                "No comments yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        } else {
            item.comments.forEach { comment ->
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    Row {
                        Text(
                            comment.authorName,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (comment.isMine) {
                                ParityThemeDefaults.colors.positive
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                        Text(
                            " · ${comment.dateText}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(comment.content, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = item.commentDraft,
                onValueChange = onDraftChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Add a comment…") },
                singleLine = true,
                enabled = !item.isPostingComment,
            )
            IconButton(
                onClick = onPost,
                enabled = item.commentDraft.isNotBlank() && !item.isPostingComment,
            ) {
                if (item.isPostingComment) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Post comment",
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }
    }
}

@Composable
private fun AttachmentsSection(
    item: LedgerRow,
    onAdd: () -> Unit,
    onOpen: (AttachmentRow) -> Unit,
    onDelete: (AttachmentRow) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    ) {
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        if (item.attachments.isNullOrEmpty()) {
            Text(
                "No attachments yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        } else {
            item.attachments.forEach { att ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onOpen(att) }
                            .padding(vertical = 4.dp),
                    ) {
                        Text(
                            att.filename,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                        Text(
                            att.sizeText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (att.isDownloading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    if (att.isMine) {
                        IconButton(onClick = { onDelete(att) }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete attachment",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (item.isUploadingAttachment) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(
                    "Uploading…",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            } else {
                TextAction("Add file", MaterialTheme.colorScheme.tertiary, bold = true, onClick = onAdd)
            }
        }
    }
}

/** A file the user picked, resolved to what the upload endpoint needs. */
private data class PickedFile(
    val filename: String,
    val contentType: String,
    val bytes: ByteArray,
)

/**
 * Resolve a picked content [Uri] to its bytes, display name, and MIME
 * type. Runs off the main thread; returns null if the file can't be read.
 */
private suspend fun readPickedFile(context: Context, uri: Uri): PickedFile? =
    withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val mime = resolver.getType(uri) ?: "application/octet-stream"
            var name = "attachment"
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx)?.let { name = it }
                }
            }
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@withContext null
            PickedFile(name, mime, bytes)
        } catch (_: Exception) {
            null
        }
    }

/**
 * Write attachment bytes to a private cache file and return a
 * FileProvider content URI a viewer app can read. Runs off the main
 * thread (callers wrap in the IO dispatcher).
 */
private fun writeToCache(context: Context, filename: String, bytes: ByteArray): Uri {
    val dir = File(context.cacheDir, "attachments").apply { mkdirs() }
    // Sanitize: keep only the leaf name so a crafted filename can't escape.
    val safeName = File(filename).name.ifBlank { "attachment" }
    val outFile = File(dir, safeName)
    outFile.writeBytes(bytes)
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile)
}
