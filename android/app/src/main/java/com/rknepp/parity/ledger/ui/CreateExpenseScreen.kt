package com.rknepp.parity.ledger.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import com.rknepp.parity.ui.theme.ParityMoney
import com.rknepp.parity.ui.theme.ParityThemeDefaults
import com.rknepp.parity.ui.theme.PillShape
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
                        .padding(24.dp),
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
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        OutlinedTextField(
            value = state.amountInput,
            onValueChange = vm::updateAmount,
            label = { Text("Total amount") },
            suffix = { Text(state.currencyCode) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
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

        OutcomePreview(state = state)

        if (state.error != null) {
            Text(
                text = state.error!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Button(
            onClick = vm::submit,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = PillShape,
            elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
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
                Text("Add expense", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun SplitSection(state: CreateExpenseState, vm: CreateExpenseViewModel) {
    Column {
        Text(
            "SPLIT",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "You paid the full amount. Choose how much of it " +
                "${state.counterpartyName} owes you.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
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
    }
}

/**
 * Persistent outcome line, recomputed on every amount/split change:
 * the single sentence answering "what does this do to our balance?"
 */
@Composable
private fun OutcomePreview(state: CreateExpenseState) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Column(modifier = Modifier.padding(top = 16.dp)) {
        if (state.totalCents <= 0L) {
            Text(
                "Enter an amount to see the split.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (state.counterpartyShareCents <= 0L) {
            Text(
                "Your treat — ${state.counterpartyName} owes nothing.",
                style = MaterialTheme.typography.bodyLarge,
            )
        } else {
            Text(
                "${state.counterpartyName} will owe you",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                formatCents(state.counterpartyShareCents, state.currencyCode),
                style = ParityMoney.screen,
                color = ParityThemeDefaults.colors.positive,
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                "your share ${formatCents(state.payerShareCents, state.currencyCode)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
