package com.rknepp.parity.relationships.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rknepp.parity.app.LocalServiceLocator

private val CommonCurrencies = listOf("USD", "EUR", "GBP", "CAD", "AUD")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateRelationshipScreen(
    onBack: () -> Unit,
    onCreated: () -> Unit,
) {
    val locator = LocalServiceLocator.current
    val vm: CreateRelationshipViewModel =
        viewModel(factory = CreateRelationshipViewModel.factory(locator))
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
                title = { Text("Invite someone") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Invite another Parity user to share a two-person ledger. " +
                    "They'll need to accept before entries can be added.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = state.username,
                onValueChange = vm::updateUsername,
                label = { Text("Their username") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !state.isSubmitting,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrect = false,
                    imeAction = ImeAction.Next,
                ),
            )

            Column {
                Text("Currency", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Fixed for the lifetime of the relationship — every entry in this " +
                        "ledger uses it.",
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
                    CommonCurrencies.forEach { code ->
                        FilterChip(
                            selected = state.currencyCode == code,
                            onClick = { vm.updateCurrencyCode(code) },
                            label = { Text(code) },
                            enabled = !state.isSubmitting,
                        )
                    }
                }
                OutlinedTextField(
                    value = state.currencyCode,
                    onValueChange = vm::updateCurrencyCode,
                    label = { Text("Currency code") },
                    supportingText = { Text("Any ISO 4217 code, e.g. JPY, CHF, SEK") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    singleLine = true,
                    enabled = !state.isSubmitting,
                    isError = state.currencyCode.isNotEmpty() && !state.currencyValid,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        autoCorrect = false,
                        imeAction = ImeAction.Done,
                    ),
                )
            }

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
                    state.username.isNotBlank() &&
                    state.currencyValid,
            ) {
                if (state.isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(2.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("Send invite")
                }
            }
        }
    }
}
