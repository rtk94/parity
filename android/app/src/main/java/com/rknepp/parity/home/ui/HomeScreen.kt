package com.rknepp.parity.home.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rknepp.parity.app.LocalServiceLocator
import com.rknepp.parity.relationships.ui.formatCents
import com.rknepp.parity.ui.components.EmptyState
import com.rknepp.parity.ui.components.ErrorState
import com.rknepp.parity.ui.components.InitialsAvatar
import com.rknepp.parity.ui.components.LoadingState
import com.rknepp.parity.ui.theme.ParityMoney
import com.rknepp.parity.ui.theme.ParityThemeDefaults
import java.time.LocalTime
import kotlin.math.absoluteValue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToRelationships: () -> Unit,
    onNavigateToRelationshipDetail: (Long) -> Unit,
    onNavigateToCreateRelationship: () -> Unit,
) {
    val locator = LocalServiceLocator.current
    val vm: HomeViewModel = viewModel(factory = HomeViewModel.factory(locator))
    val state by vm.state.collectAsState()
    val isRefreshing by vm.isRefreshing.collectAsState()

    // Keep the dashboard current when returning from other screens.
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }

    Scaffold { padding ->
        when (val s = state) {
            HomeState.Loading -> LoadingState(modifier = Modifier.padding(padding))
            HomeState.Error -> ErrorState(
                message = "Couldn't load your overview.",
                onRetry = { vm.reload() },
                modifier = Modifier.padding(padding),
            )
            is HomeState.Loaded -> {
                if (s.data.activeRelationships.isEmpty() &&
                    s.data.pending.isEmpty() &&
                    s.data.invitesForMe == 0 &&
                    s.data.invitesSent == 0
                ) {
                    Column(modifier = Modifier.padding(padding)) {
                        Greeting(
                            name = s.data.user.displayName,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                        )
                        EmptyState(
                            icon = Icons.Default.Person,
                            title = "Welcome to Parity",
                            body = "Invite someone to start a shared ledger. Every entry needs " +
                                "both of you to confirm, so the balance is always agreed on.",
                            actionLabel = "Invite someone",
                            onAction = onNavigateToCreateRelationship,
                        )
                    }
                } else {
                    PullToRefreshBox(
                        isRefreshing = isRefreshing,
                        onRefresh = vm::pullRefresh,
                        modifier = Modifier
                            .padding(padding)
                            .fillMaxSize(),
                    ) {
                    DashboardContent(
                        data = s.data,
                        onConfirm = vm::confirm,
                        onDecline = vm::decline,
                        onNavigateToRelationships = onNavigateToRelationships,
                        onNavigateToRelationshipDetail = onNavigateToRelationshipDetail,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp),
                    )
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardContent(
    data: HomeData,
    onConfirm: (PendingItem) -> Unit,
    onDecline: (PendingItem) -> Unit,
    onNavigateToRelationships: () -> Unit,
    onNavigateToRelationshipDetail: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Greeting(name = data.user.displayName, modifier = Modifier.padding(top = 16.dp))

        BalanceBlock(positions = data.positions)

        if (data.pending.isNotEmpty()) {
            PendingSection(items = data.pending, onConfirm = onConfirm, onDecline = onDecline)
        }

        if (data.invitesForMe > 0 || data.invitesSent > 0) {
            InvitesRow(
                invitesForMe = data.invitesForMe,
                invitesSent = data.invitesSent,
                onClick = onNavigateToRelationships,
            )
        }

        if (data.activeRelationships.isNotEmpty()) {
            PeopleSection(
                relationships = data.activeRelationships,
                onViewAll = onNavigateToRelationships,
                onOpen = onNavigateToRelationshipDetail,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun Greeting(name: String, modifier: Modifier = Modifier) {
    val hour = LocalTime.now().hour
    val greeting = when {
        hour < 5 -> "Up late"
        hour < 12 -> "Good morning"
        hour < 18 -> "Good afternoon"
        else -> "Good evening"
    }
    Column(modifier = modifier) {
        Text(
            "$greeting,",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(name, style = MaterialTheme.typography.headlineMedium)
    }
}

/** Money color: green when owed to you, red when you owe. */
@Composable
private fun moneyColor(cents: Long) =
    if (cents >= 0) ParityThemeDefaults.colors.positive else MaterialTheme.colorScheme.error

@Composable
private fun LabelCaps(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = color)
}

@Composable
private fun BalanceBlock(positions: List<CurrencyPosition>, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LabelCaps("Overall", MaterialTheme.colorScheme.onSurfaceVariant)
        if (positions.isEmpty()) {
            Text("All square", style = ParityMoney.screen)
        } else {
            positions.forEach { position ->
                val net = position.netCents
                val figureStyle = if (positions.size == 1) ParityMoney.hero else ParityMoney.screen
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        formatCents(net.absoluteValue, position.currencyCode),
                        style = figureStyle,
                        color = if (net == 0L) MaterialTheme.colorScheme.onSurface else moneyColor(net),
                    )
                    Text(
                        when {
                            net == 0L -> "settled up"
                            net > 0L -> "owed to you"
                            else -> "you owe overall"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        // Editorial accent: a short green rule under the balance.
        Box(
            Modifier
                .width(40.dp)
                .height(2.dp)
                .background(ParityThemeDefaults.colors.positive),
        )
    }
}

@Composable
private fun PendingSection(
    items: List<PendingItem>,
    onConfirm: (PendingItem) -> Unit,
    onDecline: (PendingItem) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        LabelCaps(
            if (items.size == 1) "1 needs your confirmation"
            else "${items.size} need your confirmation",
            ParityThemeDefaults.colors.pending,
        )
        items.forEach { item ->
            PendingRow(item, onConfirm = { onConfirm(item) }, onDecline = { onDecline(item) })
        }
    }
}

@Composable
private fun PendingRow(item: PendingItem, onConfirm: () -> Unit, onDecline: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(item.description, style = MaterialTheme.typography.titleMedium)
            Text(item.amountText, style = ParityMoney.row)
        }
        Text(
            item.counterpartyName + " · " + if (item.kind == PendingItem.Kind.EXPENSE) "expense" else "payment",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 3.dp),
        )
        Row(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text(
                "Confirm",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clickable(onClick = onConfirm)
                    .padding(vertical = 14.dp),
            )
            Text(
                "Decline",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clickable(onClick = onDecline)
                    .padding(vertical = 14.dp),
            )
        }
    }
}

@Composable
private fun InvitesRow(invitesForMe: Int, invitesSent: Int, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (invitesForMe > 0) {
            LabelCaps(
                if (invitesForMe == 1) "1 invitation waiting for you"
                else "$invitesForMe invitations waiting for you",
                ParityThemeDefaults.colors.pending,
            )
        }
        if (invitesSent > 0) {
            Text(
                if (invitesSent == 1) "1 sent invite awaiting acceptance"
                else "$invitesSent sent invites awaiting acceptance",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PeopleSection(
    relationships: List<HomeRelationship>,
    onViewAll: () -> Unit,
    onOpen: (Long) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "People",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onViewAll) { Text("View all") }
        }
        relationships.forEachIndexed { index, rel ->
            if (index > 0) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            PersonRow(rel = rel, onClick = { onOpen(rel.id) })
        }
    }
}

@Composable
private fun PersonRow(rel: HomeRelationship, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InitialsAvatar(name = rel.counterpartyName, size = 34.dp)
        Text(
            rel.counterpartyName,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        )
        val net = rel.netCentsForMe
        when {
            net == null -> {}
            net == 0L -> Text(
                "Settled",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> Text(
                formatCents(net.absoluteValue, rel.currencyCode),
                style = ParityMoney.row,
                color = moneyColor(net),
            )
        }
    }
}
