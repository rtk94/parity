package com.rknepp.parity.relationships.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rknepp.parity.app.LocalServiceLocator

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
        factory = RelationshipDetailViewModel.factory(locator, relationshipId)
    )
    val state by vm.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Relationship Details") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (val s = state) {
                RelationshipDetailState.Loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                RelationshipDetailState.Error -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Failed to load details", color = MaterialTheme.colorScheme.error)
                        Button(onClick = { vm.reload() }, modifier = Modifier.padding(top = 16.dp)) {
                            Text("Retry")
                        }
                    }
                }
                is RelationshipDetailState.Loaded -> {
                    RelationshipDetailContent(
                        data = s.data,
                        onAccept = { vm.accept() },
                        onReject = { vm.reject() },
                        onAddExpense = onNavigateToCreateExpense,
                        onAddPayment = onNavigateToCreatePayment,
                        onConfirmExpense = { vm.confirmExpense(it) },
                        onDiscardExpense = { vm.discardExpense(it) },
                        onReverseExpense = { vm.reverseExpense(it) },
                        onConfirmPayment = { vm.confirmPayment(it) },
                        onDiscardPayment = { vm.discardPayment(it) },
                        onReversePayment = { vm.reversePayment(it) },
                        onToggleComments = { id, type -> vm.toggleComments(id, type) },
                        onPostComment = { id, type, content -> vm.postComment(id, type, content) },
                    )
                }
            }
        }
    }
}

@Composable
fun RelationshipDetailContent(
    data: RelationshipDetailData,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onAddExpense: () -> Unit,
    onAddPayment: () -> Unit,
    onConfirmExpense: (Long) -> Unit,
    onDiscardExpense: (Long) -> Unit,
    onReverseExpense: (Long) -> Unit,
    onConfirmPayment: (Long) -> Unit,
    onDiscardPayment: (Long) -> Unit,
    onReversePayment: (Long) -> Unit,
    onToggleComments: (Long, String) -> Unit,
    onPostComment: (Long, String, String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(data.counterpartyName, style = MaterialTheme.typography.headlineSmall)
                    Text("@${data.counterpartyUsername}", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Status: ${data.status.replaceFirstChar { it.uppercase() }}", modifier = Modifier.padding(top = 8.dp))
                    Text("Currency: ${data.currencyCode}")

                    if (data.canAccept || data.canReject) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            if (data.canReject) {
                                Button(onClick = onReject, modifier = Modifier.padding(end = 8.dp)) {
                                    if (data.canAccept) Text("Reject") else Text("Cancel Invite")
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
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Confirmed Balance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    BalanceLineView(line = data.confirmed)
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Projected Balance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    BalanceLineView(line = data.projected)
                }
            }
        }

        if (data.status == "accepted") {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Button(onClick = onAddExpense) {
                        Text("Add Expense")
                    }
                    Button(onClick = onAddPayment) {
                        Text("Add Payment")
                    }
                }
            }
        }

        items(data.ledgerItems, key = { "${it.type}_${it.id}" }) { item ->
            LedgerItemCard(
                item = item,
                onConfirm = { if (item.type == "expense") onConfirmExpense(item.id) else onConfirmPayment(item.id) },
                onDiscard = { if (item.type == "expense") onDiscardExpense(item.id) else onDiscardPayment(item.id) },
                onReverse = { if (item.type == "expense") onReverseExpense(item.id) else onReversePayment(item.id) },
                onToggleComments = { onToggleComments(item.id, item.type) },
                onPostComment = { content -> onPostComment(item.id, item.type, content) }
            )
        }
        
        item {
            // Bottom padding
            Box(modifier = Modifier.padding(16.dp))
        }
    }
}

@Composable
fun LedgerItemCard(
    item: LedgerRow,
    onConfirm: () -> Unit,
    onDiscard: () -> Unit,
    onReverse: () -> Unit,
    onToggleComments: () -> Unit,
    onPostComment: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (item.type == "expense") "Expense" else "Payment",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = item.status.replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Text(item.description, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
            if (item.category != null) {
                Text(
                    text = item.category,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            
            val actionText = if (item.type == "expense") {
                if (item.isPayer) "You paid" else "Counterparty paid"
            } else {
                if (item.isPayer) "You sent" else "You received"
            }
            Text("$actionText ${item.amountText}", style = MaterialTheme.typography.bodyMedium)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Button(onClick = onToggleComments, modifier = Modifier.padding(end = 8.dp)) {
                    Text(if (item.comments != null) "Hide Comments" else "Comments")
                }
                if (item.canDiscard) {
                    Button(onClick = onDiscard, modifier = Modifier.padding(end = 8.dp)) {
                        Text("Discard")
                    }
                }
                if (item.canReverse) {
                    Button(onClick = onReverse, modifier = Modifier.padding(end = 8.dp)) {
                        Text("Reverse")
                    }
                }
                if (item.canConfirm) {
                    Button(onClick = onConfirm) {
                        Text("Confirm")
                    }
                }
            }

            if (item.isLoadingComments) {
                CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp).align(Alignment.CenterHorizontally))
            } else if (item.comments != null) {
                Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    if (item.comments.isEmpty()) {
                        Text("No comments yet.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
                    } else {
                        item.comments.forEach { comment ->
                            Text(
                                text = comment.content,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                    }

                    var commentText by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.OutlinedTextField(
                            value = commentText,
                            onValueChange = { commentText = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Add a comment...") },
                            singleLine = true
                        )
                        Button(
                            onClick = {
                                onPostComment(commentText)
                                commentText = ""
                            },
                            modifier = Modifier.padding(start = 8.dp),
                            enabled = commentText.isNotBlank()
                        ) {
                            Text("Post")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BalanceLineView(line: BalanceLine) {
    if (line.settled) {
        Text("Settled up (${line.amountText})", modifier = Modifier.padding(top = 8.dp))
    } else if (line.youOwe) {
        Text("You owe ${line.counterpartyName}:", modifier = Modifier.padding(top = 8.dp))
        Text(line.amountText, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleLarge)
    } else {
        Text("${line.counterpartyName} owes you:", modifier = Modifier.padding(top = 8.dp))
        Text(line.amountText, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleLarge)
    }
}
