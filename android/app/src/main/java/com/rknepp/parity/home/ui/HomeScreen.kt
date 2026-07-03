package com.rknepp.parity.home.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rknepp.parity.app.LocalServiceLocator
import com.rknepp.parity.relationships.ui.formatCents
import com.rknepp.parity.ui.components.EmptyState
import com.rknepp.parity.ui.components.ErrorState
import com.rknepp.parity.ui.components.InitialsAvatar
import com.rknepp.parity.ui.components.LoadingState
import com.rknepp.parity.ui.theme.ParityThemeDefaults
import java.time.LocalTime

@Composable
fun HomeScreen(
    onNavigateToRelationships: () -> Unit,
    onNavigateToRelationshipDetail: (Long) -> Unit,
    onNavigateToCreateRelationship: () -> Unit,
) {
    val locator = LocalServiceLocator.current
    val vm: HomeViewModel = viewModel(factory = HomeViewModel.factory(locator))
    val state by vm.state.collectAsState()

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
                    s.data.invitesForMe == 0 &&
                    s.data.invitesSent == 0
                ) {
                    Column(modifier = Modifier.padding(padding)) {
                        Greeting(
                            name = s.data.user.displayName,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
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
                    DashboardContent(
                        data = s.data,
                        onNavigateToRelationships = onNavigateToRelationships,
                        onNavigateToRelationshipDetail = onNavigateToRelationshipDetail,
                        modifier = Modifier
                            .padding(padding)
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DashboardContent(
    data: HomeData,
    onNavigateToRelationships: () -> Unit,
    onNavigateToRelationshipDetail: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Greeting(
            name = data.user.displayName,
            modifier = Modifier.padding(top = 16.dp),
        )

        PositionCard(positions = data.positions)

        if (data.invitesForMe > 0 || data.invitesSent > 0) {
            InvitesCard(
                invitesForMe = data.invitesForMe,
                invitesSent = data.invitesSent,
                onClick = onNavigateToRelationships,
            )
        }

        if (data.activeRelationships.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            ) {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Relationships",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onNavigateToRelationships) {
                            Text("View all")
                        }
                    }
                    data.activeRelationships.forEachIndexed { index, rel ->
                        if (index > 0) {
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        }
                        HomeRelationshipRow(
                            rel = rel,
                            onClick = { onNavigateToRelationshipDetail(rel.id) },
                        )
                    }
                }
            }
        }

        // Breathing room above the bottom navigation bar.
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

@Composable
private fun PositionCard(positions: List<CurrencyPosition>, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
        ),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                "Overall position",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (positions.isEmpty()) {
                Text(
                    "No confirmed balances yet",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 6.dp),
                )
            } else {
                positions.forEach { position ->
                    PositionLine(position, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun PositionLine(position: CurrencyPosition, modifier: Modifier = Modifier) {
    val net = position.netCents
    when {
        net == 0L -> Text(
            "Settled up in ${position.currencyCode}",
            style = MaterialTheme.typography.titleLarge,
            modifier = modifier,
        )
        net > 0L -> Column(modifier = modifier) {
            Text(
                formatCents(net, position.currencyCode),
                style = MaterialTheme.typography.headlineSmall,
                color = ParityThemeDefaults.colors.positive,
            )
            Text(
                "owed to you",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        else -> Column(modifier = modifier) {
            Text(
                formatCents(-net, position.currencyCode),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                "you owe overall",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InvitesCard(
    invitesForMe: Int,
    invitesSent: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.MailOutline, contentDescription = null)
            Column(modifier = Modifier.padding(start = 12.dp)) {
                if (invitesForMe > 0) {
                    Text(
                        if (invitesForMe == 1) "1 invitation waiting for you"
                        else "$invitesForMe invitations waiting for you",
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                if (invitesSent > 0) {
                    Text(
                        if (invitesSent == 1) "1 sent invite awaiting acceptance"
                        else "$invitesSent sent invites awaiting acceptance",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeRelationshipRow(rel: HomeRelationship, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InitialsAvatar(name = rel.counterpartyName, size = 36.dp)
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
            net > 0L -> Text(
                formatCents(net, rel.currencyCode),
                style = MaterialTheme.typography.titleSmall,
                color = ParityThemeDefaults.colors.positive,
            )
            else -> Text(
                formatCents(net, rel.currencyCode),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
