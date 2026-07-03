package com.rknepp.parity.relationships.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rknepp.parity.app.LocalServiceLocator
import com.rknepp.parity.ui.components.ConfirmActionDialog
import com.rknepp.parity.ui.components.CurrencyChip
import com.rknepp.parity.ui.components.EmptyState
import com.rknepp.parity.ui.components.ErrorState
import com.rknepp.parity.ui.components.InitialsAvatar
import com.rknepp.parity.ui.components.LoadingState
import com.rknepp.parity.ui.theme.ParityThemeDefaults

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelationshipListScreen(
    onNavigateToDetail: (Long) -> Unit,
    onNavigateToCreate: () -> Unit,
) {
    val locator = LocalServiceLocator.current
    val vm: RelationshipListViewModel =
        viewModel(factory = RelationshipListViewModel.factory(locator))
    val state by vm.state.collectAsState()
    val actionError by vm.actionError.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    var confirmRejectRow by remember { mutableStateOf<RelationshipRow?>(null) }

    // Revalidate whenever the screen returns to the foreground, e.g.
    // after creating a relationship — otherwise the list goes stale.
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

    confirmRejectRow?.let { row ->
        ConfirmActionDialog(
            title = if (row.canAccept) "Decline invitation?" else "Cancel invite?",
            text = if (row.canAccept) {
                "Declining removes the invitation from ${row.counterpartyName}. " +
                    "Any pending entries bundled with it are discarded."
            } else {
                "This withdraws your invitation to ${row.counterpartyName}. " +
                    "Any pending entries bundled with it are discarded."
            },
            confirmLabel = if (row.canAccept) "Decline" else "Cancel invite",
            destructive = true,
            onConfirm = {
                vm.reject(row.id)
                confirmRejectRow = null
            },
            onDismiss = { confirmRejectRow = null },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Relationships") })
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = onNavigateToCreate) {
                Icon(Icons.Filled.Add, contentDescription = "Add Relationship")
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (val s = state) {
                RelationshipListState.Loading -> LoadingState()
                RelationshipListState.Empty -> EmptyState(
                    icon = Icons.Default.Person,
                    title = "No relationships yet",
                    body = "Invite someone to start tracking shared expenses and payments together.",
                    actionLabel = "Invite someone",
                    onAction = onNavigateToCreate,
                )
                RelationshipListState.Error -> ErrorState(
                    message = "Couldn't load your relationships.",
                    onRetry = { vm.reload() },
                )
                is RelationshipListState.Loaded -> {
                    LazyColumn(
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 8.dp,
                            bottom = 96.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(s.rows, key = { it.id }) { row ->
                            RelationshipRowCard(
                                row = row,
                                onClick = { onNavigateToDetail(row.id) },
                                onAccept = { vm.accept(row.id) },
                                onReject = { confirmRejectRow = row },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RelationshipRowCard(
    row: RelationshipRow,
    onClick: () -> Unit,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InitialsAvatar(name = row.counterpartyName)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                Text(row.counterpartyName, style = MaterialTheme.typography.titleMedium)
                Text(
                    "@${row.counterpartyUsername}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (row.status == "pending") {
                    Text(
                        text = if (row.canAccept) "Invited you" else "Invite sent",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                BalanceSummaryText(row)
                CurrencyChip(row.currencyCode, modifier = Modifier.padding(top = 4.dp))
            }
        }
        if (row.canAccept || row.canReject) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (row.canReject) {
                    OutlinedButton(onClick = onReject, modifier = Modifier.padding(end = 8.dp)) {
                        Text(if (row.canAccept) "Decline" else "Cancel invite")
                    }
                }
                if (row.canAccept) {
                    Button(onClick = onAccept) {
                        Text("Accept")
                    }
                }
            }
        }
    }
}

@Composable
private fun BalanceSummaryText(row: RelationshipRow) {
    val net = row.netCentsForMe
    when {
        row.status != "accepted" || net == null -> {}
        net == 0L -> Text(
            "Settled up",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        net > 0L -> Column(horizontalAlignment = Alignment.End) {
            Text(
                "owes you",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                formatCents(net, row.currencyCode),
                style = MaterialTheme.typography.titleMedium,
                color = ParityThemeDefaults.colors.positive,
            )
        }
        else -> Column(horizontalAlignment = Alignment.End) {
            Text(
                "you owe",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                formatCents(-net, row.currencyCode),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
