package com.rknepp.parity.auth.ui.email

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rknepp.parity.R
import com.rknepp.parity.app.LocalServiceLocator
import com.rknepp.parity.ui.components.ParityLogo
import com.rknepp.parity.ui.theme.PillShape

/**
 * Gates [content] behind a recovery email.
 *
 * Accounts predating the requirement have none on file and cannot be
 * recovered, so the prompt is unskippable — apart from signing out. It
 * wraps the app's content rather than occupying a route of its own, so
 * it covers a fresh login and a cold relaunch alike without touching the
 * back stack.
 */
@Composable
fun RecoveryEmailGate(content: @Composable () -> Unit) {
    val locator = LocalServiceLocator.current
    val vm: RecoveryEmailViewModel = viewModel(factory = RecoveryEmailViewModel.factory(locator))
    val state by vm.state.collectAsState()

    when (state.phase) {
        GatePhase.Satisfied -> content()
        GatePhase.Loading -> CenteredProgress()
        GatePhase.Failed -> GateUnavailable(onRetry = vm::refresh)
        GatePhase.Prompting -> RecoveryEmailPrompt(
            email = state.email,
            submitting = state.submitting,
            error = state.error,
            onEmailChange = vm::onEmailChange,
            onSave = vm::save,
            onSignOut = vm::signOut,
        )
    }
}

@Composable
private fun CenteredProgress() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun GateUnavailable(onRetry: () -> Unit) {
    // Deliberately does not fall through to the app: "we could not check"
    // is not "an address is on file", and guessing wrong lets exactly the
    // unrecoverable accounts this gate exists for slip past.
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.recovery_email_error_network),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.recovery_email_retry))
            }
        }
    }
}

@Composable
private fun RecoveryEmailPrompt(
    email: String,
    submitting: Boolean,
    error: RecoveryEmailError?,
    onEmailChange: (String) -> Unit,
    onSave: () -> Unit,
    onSignOut: () -> Unit,
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 56.dp, bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ParityLogo(size = 56)
                Text(
                    text = stringResource(R.string.recovery_email_title),
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.recovery_email_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedTextField(
                value = email,
                onValueChange = onEmailChange,
                label = { Text(stringResource(R.string.recovery_email_label)) },
                singleLine = true,
                enabled = !submitting,
                isError = error != null,
                supportingText = { Text(stringResource(R.string.recovery_email_helper)) },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrect = false,
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = onSave,
                enabled = !submitting && email.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = PillShape,
                elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
            ) {
                if (submitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(2.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(
                        stringResource(R.string.recovery_email_save),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }

            error?.let { err ->
                Text(
                    text = errorMessage(err),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            TextButton(
                onClick = onSignOut,
                enabled = !submitting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.recovery_email_sign_out))
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun errorMessage(error: RecoveryEmailError): String = when (error) {
    RecoveryEmailError.Invalid -> stringResource(R.string.recovery_email_error_invalid)
    RecoveryEmailError.Taken -> stringResource(R.string.recovery_email_error_taken)
    RecoveryEmailError.Network -> stringResource(R.string.recovery_email_error_network)
    RecoveryEmailError.Generic -> stringResource(R.string.recovery_email_error_generic)
}
