package com.rknepp.parity.ledger.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rknepp.parity.app.LocalServiceLocator
import com.rknepp.parity.relationships.ui.formatCents
import com.rknepp.parity.ui.components.LoadingState
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateExpenseScreen(
    relationshipId: Long,
    onBack: () -> Unit,
    onCreated: () -> Unit,
) {
    val locator = LocalServiceLocator.current
    val vm: CreateExpenseViewModel =
        viewModel(factory = CreateExpenseViewModel.factory(locator, relationshipId))
    val state by vm.state.collectAsState()

    LaunchedEffect(state.success) {
        if (state.success) {
            vm.resetSuccess()
            onCreated()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add expense") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (!state.isReady) {
            if (state.error != null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(16.dp),
                ) {
                    Text(
                        text = state.error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                LoadingState(modifier = Modifier.padding(padding))
            }
        } else {
            CreateExpenseForm(state = state, vm = vm, padding = padding)
        }
    }
}

@Composable
private fun CreateExpenseForm(
    state: CreateExpenseState,
    vm: CreateExpenseViewModel,
    padding: PaddingValues,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OutlinedTextField(
            value = state.amountInput,
            onValueChange = vm::updateAmount,
            label = { Text("Total amount") },
            suffix = { Text(state.currencyCode) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            enabled = !state.isSubmitting,
        )

        OutlinedTextField(
            value = state.description,
            onValueChange = vm::updateDescription,
            label = { Text("Description") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !state.isSubmitting,
        )

        OutlinedTextField(
            value = state.category,
            onValueChange = vm::updateCategory,
            label = { Text("Category (optional)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !state.isSubmitting,
        )

        SplitSection(state = state, vm = vm)

        if (state.error != null) {
            Text(
                text = state.error!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Button(
            onClick = vm::submit,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isSubmitting &&
                state.totalCents > 0 &&
                state.description.isNotBlank(),
        ) {
            if (state.isSubmitting) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(2.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text("Save expense")
            }
        }
    }
}

@Composable
private fun SplitSection(state: CreateExpenseState, vm: CreateExpenseViewModel) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Split", style = MaterialTheme.typography.titleMedium)
            Text(
                "You paid the full amount. Choose how much of it " +
                    "${state.counterpartyName} owes you.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = state.counterpartySharePercent == 0,
                    onClick = { vm.updateCounterpartySharePercent(0) },
                    label = { Text("Your treat") },
                    enabled = !state.isSubmitting,
                )
                FilterChip(
                    selected = state.counterpartySharePercent == 50,
                    onClick = { vm.updateCounterpartySharePercent(50) },
                    label = { Text("Split evenly") },
                    enabled = !state.isSubmitting,
                )
                FilterChip(
                    selected = state.counterpartySharePercent == 100,
                    onClick = { vm.updateCounterpartySharePercent(100) },
                    label = { Text("They owe all") },
                    enabled = !state.isSubmitting,
                )
            }

            Slider(
                value = state.counterpartySharePercent.toFloat(),
                onValueChange = { vm.updateCounterpartySharePercent(it.roundToInt()) },
                valueRange = 0f..100f,
                enabled = !state.isSubmitting,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            )

            val shareText = formatCents(state.counterpartyShareCents, state.currencyCode)
            val yourText = formatCents(state.payerShareCents, state.currencyCode)
            Text(
                text = "${state.counterpartyName} owes you $shareText " +
                    "(${state.counterpartySharePercent}%) · your share $yourText",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
