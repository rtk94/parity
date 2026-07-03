package com.rknepp.parity.relationships.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rknepp.parity.app.LocalServiceLocator
import com.rknepp.parity.ui.components.ConfirmActionDialog
import com.rknepp.parity.ui.components.ErrorState
import com.rknepp.parity.ui.components.LoadingState
import com.rknepp.parity.ui.components.StatusChip
import com.rknepp.parity.ui.theme.ParityThemeDefaults

/** Destructive/consequential actions gated behind a confirm dialog. */
private sealed interface PendingAction {
    data object RejectInvite : PendingAction
    data class Discard(val row: LedgerRow) : PendingAction
    data class Reverse(val row: LedgerRow) : PendingAction
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

    val snackbarHostState = remember { SnackbarHostState() }
    var pendingAction by remember { mutableStateOf<PendingAction?>(null) }

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
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (data.canAccept || data.canReject) {
            item {
                InviteBanner(
                    data = data,
                    onAccept = onAccept,
                    onReject = onReject,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }

        item {
            BalanceCard(
                data = data,
                modifier = Modifier.padding(top = if (data.canAccept || data.canReject) 0.dp else 12.dp),
            )
        }

        if (data.status == "accepted") {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    FilledTonalButton(
                        onClick = onAddExpense,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            Icons.Default.ShoppingCart,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text("Add expense", modifier = Modifier.padding(start = 8.dp))
                    }
                    FilledTonalButton(
                        onClick = onAddPayment,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text("Log payment", modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }

        item {
            Text(
                "Activity",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        if (data.ledgerItems.isEmpty()) {
            item {
                Text(
                    text = "No entries yet. Expenses and payments you add will show up here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }

        items(data.ledgerItems, key = { "${it.type}_${it.id}" }) { item ->
            LedgerItemCard(
                item = item,
                onConfirm = { onConfirm(item) },
                onDiscard = { onDiscard(item) },
                onReverse = { onReverse(item) },
                onToggleComments = { onToggleComments(item) },
                onCommentDraftChange = { draft -> onCommentDraftChange(item, draft) },
                onPostComment = { onPostComment(item) },
            )
        }

        item {
            Box(modifier = Modifier.padding(16.dp))
        }
    }
}

@Composable
private fun InviteBanner(
    data: RelationshipDetailData,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = if (data.canAccept) {
                    "${data.counterpartyName} invited you to share a ${data.currencyCode} ledger."
                } else {
                    "Waiting for ${data.counterpartyName} to accept your ${data.currencyCode} invite."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                if (data.canReject) {
                    OutlinedButton(onClick = onReject, modifier = Modifier.padding(end = 8.dp)) {
                        Text(if (data.canAccept) "Decline" else "Cancel invite")
                    }
                }
                if (data.canAccept) {
                    Button(onClick = onAccept) {
                        Text("Accept")
                    }
                }
            }
        }
    }
}

@Composable
private fun BalanceCard(data: RelationshipDetailData, modifier: Modifier = Modifier) {
    val confirmed = data.confirmed
    val projected = data.projected
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                "Confirmed balance",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                confirmed.settled -> Text(
                    "All settled up",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
                confirmed.youOwe -> {
                    Text(
                        confirmed.amountText,
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        "you owe ${confirmed.counterpartyName}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    Text(
                        confirmed.amountText,
                        style = MaterialTheme.typography.headlineMedium,
                        color = ParityThemeDefaults.colors.positive,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        "${confirmed.counterpartyName} owes you",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (projected != confirmed) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                val projectedText = when {
                    projected.settled -> "settled up"
                    projected.youOwe -> "you owe ${projected.amountText}"
                    else -> "${projected.counterpartyName} owes you ${projected.amountText}"
                }
                Text(
                    "Once pending entries confirm: $projectedText",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }
}

@Composable
private fun LedgerItemCard(
    item: LedgerRow,
    onConfirm: () -> Unit,
    onDiscard: () -> Unit,
    onReverse: () -> Unit,
    onToggleComments: () -> Unit,
    onCommentDraftChange: (String) -> Unit,
    onPostComment: () -> Unit,
) {
    val inactive = item.status == "discarded"
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (inactive) 0.6f else 1f),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EntryIcon(type = item.type)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                ) {
                    Text(item.description, style = MaterialTheme.typography.titleMedium)
                    Text(
                        item.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        Text(
                            item.dateText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (item.category != null) {
                            Text(
                                " · ${item.category}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                        if (item.isReversal) {
                            Text(
                                " · Reversal",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    DeltaText(delta = item.deltaCents, amountText = item.amountText)
                    StatusChip(item.status, modifier = Modifier.padding(top = 4.dp))
                }
            }

            val hasActions = item.canConfirm || item.canDiscard || item.canReverse
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = if (hasActions) 8.dp else 0.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onToggleComments) {
                    Text(if (item.comments != null) "Hide comments" else "Comments")
                }
                if (item.canReverse) {
                    TextButton(onClick = onReverse) {
                        Text("Reverse")
                    }
                }
                if (item.canDiscard) {
                    OutlinedButton(
                        onClick = onDiscard,
                        modifier = Modifier.padding(start = 8.dp),
                    ) {
                        Text("Discard")
                    }
                }
                if (item.canConfirm) {
                    Button(
                        onClick = onConfirm,
                        modifier = Modifier.padding(start = 8.dp),
                    ) {
                        Text("Confirm")
                    }
                }
            }

            if (item.isLoadingComments) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .size(24.dp)
                        .align(Alignment.CenterHorizontally),
                    strokeWidth = 2.dp,
                )
            } else if (item.comments != null) {
                CommentsSection(
                    item = item,
                    onDraftChange = onCommentDraftChange,
                    onPost = onPostComment,
                )
            }
        }
    }
}

@Composable
private fun EntryIcon(type: String) {
    val icon = if (type == "expense") Icons.Default.ShoppingCart else Icons.AutoMirrored.Filled.Send
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = if (type == "expense") "Expense" else "Payment",
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * Entry amount, colored by its effect on the caller: green when it
 * moves money toward you, error-red when it moves money away, muted
 * when the entry has no balance effect (discarded).
 */
@Composable
private fun DeltaText(delta: Long?, amountText: String) {
    val color: Color = when {
        delta == null -> MaterialTheme.colorScheme.onSurfaceVariant
        delta > 0L -> ParityThemeDefaults.colors.positive
        delta < 0L -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Text(
        amountText,
        style = MaterialTheme.typography.titleMedium,
        color = color,
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
        HorizontalDivider(modifier = Modifier.padding(bottom = 8.dp))
        if (item.comments.isNullOrEmpty()) {
            Text(
                "No comments yet.",
                style = MaterialTheme.typography.bodySmall,
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
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.secondary
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
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Post comment",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}
