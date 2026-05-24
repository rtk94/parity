package com.rknepp.parity.auth.ui.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rknepp.parity.R
import com.rknepp.parity.app.LocalServiceLocator

@Composable
fun ConnectToServerScreen(
    onConnected: () -> Unit,
) {
    val locator = LocalServiceLocator.current
    val vm: ConnectViewModel = viewModel(factory = ConnectViewModel.factory(locator))
    val state by vm.state.collectAsState()

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.connect_title),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = stringResource(R.string.connect_helper),
                style = MaterialTheme.typography.bodyMedium,
            )

            OutlinedTextField(
                value = state.url,
                onValueChange = vm::onUrlChange,
                label = { Text(stringResource(R.string.connect_url_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.submitting,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrect = false,
                ),
            )

            state.error?.let { err ->
                Text(
                    text = errorMessage(err),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Button(
                onClick = { vm.submit(onConnected) },
                enabled = !state.submitting && state.url.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.submitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(stringResource(R.string.connect_button))
                }
            }
        }
    }
}

@Composable
private fun errorMessage(error: ConnectError): String = when (error) {
    ConnectError.InvalidUrl -> stringResource(R.string.connect_error_invalid_url)
    ConnectError.BadScheme -> stringResource(R.string.connect_error_bad_scheme)
    ConnectError.Network -> stringResource(R.string.connect_error_network)
    is ConnectError.Http -> stringResource(R.string.connect_error_http, error.code)
    ConnectError.Unexpected -> stringResource(R.string.connect_error_unexpected)
}
