package com.rknepp.parity.ledger.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreatePaymentScreen(
    relationshipId: Long,
    onBack: () -> Unit,
    onCreated: () -> Unit,
) {
    val locator = LocalServiceLocator.current
    val vm: CreatePaymentViewModel =
        viewModel(factory = CreatePaymentViewModel.factory(locator, relationshipId))
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
                title = { Text("Log payment") },
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
            CreatePaymentForm(state = state, vm = vm, padding = padding)
        }
    }
}

@Composable
private fun CreatePaymentForm(
    state: CreatePaymentState,
    vm: CreatePaymentViewModel,
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
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
        ) {
            SegmentedButton(
                selected = state.isUserPaying,
                onClick = { vm.setIsUserPaying(true) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                enabled = !state.isSubmitting,
            ) {
                Text("You paid")
            }
            SegmentedButton(
                selected = !state.isUserPaying,
                onClick = { vm.setIsUserPaying(false) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                enabled = !state.isSubmitting,
            ) {
                Text("${state.counterpartyName} paid")
            }
        }

        OutlinedTextField(
            value = state.amountInput,
            onValueChange = vm::updateAmount,
            label = { Text("Amount") },
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

        if (state.error != null) {
            Text(
                text = state.error!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        PaymentOutcome(state = state)

        Button(
            onClick = vm::submit,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = PillShape,
            elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
            enabled = !state.isSubmitting &&
                state.amountCents > 0 &&
                state.description.isNotBlank(),
        ) {
            if (state.isSubmitting) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(2.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text("Log payment", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** Live outcome line: who paid whom, with a serif amount once entered. */
@Composable
private fun PaymentOutcome(state: CreatePaymentState) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Column(modifier = Modifier.padding(top = 16.dp)) {
        if (state.amountCents <= 0L) {
            Text(
                "Enter an amount. The payment stays pending until " +
                    "${state.counterpartyName} confirms it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                if (state.isUserPaying) {
                    "You paid ${state.counterpartyName}"
                } else {
                    "${state.counterpartyName} paid you"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                formatCents(state.amountCents, state.currencyCode),
                style = ParityMoney.screen,
                color = ParityThemeDefaults.colors.positive,
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                "pending until ${state.counterpartyName} confirms",
                style = MaterialTheme.typography.bodyMedium,
                color = ParityThemeDefaults.colors.pending,
            )
        }
    }
}
