package com.rknepp.parity.auth.ui.forgot

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rknepp.parity.R
import com.rknepp.parity.app.LocalServiceLocator
import com.rknepp.parity.ui.components.ParityLogo
import com.rknepp.parity.ui.theme.PillShape

@Composable
fun ForgotPasswordScreen(
    onResetComplete: () -> Unit,
    onBackToLogin: () -> Unit,
) {
    val locator = LocalServiceLocator.current
    val vm: ForgotPasswordViewModel = viewModel(factory = ForgotPasswordViewModel.factory(locator))
    val state by vm.state.collectAsState()

    var passwordVisible by remember { mutableStateOf(false) }

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
                    text = stringResource(R.string.forgot_title),
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(
                        when (state.phase) {
                            ResetPhase.Request -> R.string.forgot_request_subtitle
                            ResetPhase.Confirm -> R.string.forgot_confirm_subtitle
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            when (state.phase) {
                ResetPhase.Request -> RequestFields(
                    email = state.email,
                    submitting = state.submitting,
                    onEmailChange = vm::onEmailChange,
                    onSubmit = vm::requestReset,
                )
                ResetPhase.Confirm -> ConfirmFields(
                    token = state.token,
                    newPassword = state.newPassword,
                    submitting = state.submitting,
                    passwordVisible = passwordVisible,
                    onTogglePasswordVisible = { passwordVisible = !passwordVisible },
                    onTokenChange = vm::onTokenChange,
                    onNewPasswordChange = vm::onNewPasswordChange,
                    onSubmit = { vm.confirmReset(onResetComplete) },
                )
            }

            state.error?.let { err ->
                Text(
                    text = errorMessage(err),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            TextButton(
                onClick = {
                    if (state.phase == ResetPhase.Confirm) vm.backToRequest() else onBackToLogin()
                },
                enabled = !state.submitting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        when (state.phase) {
                            ResetPhase.Request -> R.string.forgot_back_to_login
                            ResetPhase.Confirm -> R.string.forgot_back_to_request
                        },
                    ),
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun RequestFields(
    email: String,
    submitting: Boolean,
    onEmailChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    OutlinedTextField(
        value = email,
        onValueChange = onEmailChange,
        label = { Text(stringResource(R.string.forgot_email_label)) },
        singleLine = true,
        enabled = !submitting,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrect = false,
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Done,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    PrimaryButton(
        label = stringResource(R.string.forgot_request_button),
        enabled = !submitting && email.isNotBlank(),
        submitting = submitting,
        onClick = onSubmit,
    )
}

@Composable
private fun ConfirmFields(
    token: String,
    newPassword: String,
    submitting: Boolean,
    passwordVisible: Boolean,
    onTogglePasswordVisible: () -> Unit,
    onTokenChange: (String) -> Unit,
    onNewPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    OutlinedTextField(
        value = token,
        onValueChange = onTokenChange,
        label = { Text(stringResource(R.string.forgot_token_label)) },
        singleLine = true,
        enabled = !submitting,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrect = false,
            imeAction = ImeAction.Next,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = newPassword,
        onValueChange = onNewPasswordChange,
        label = { Text(stringResource(R.string.forgot_new_password_label)) },
        singleLine = true,
        enabled = !submitting,
        visualTransformation = if (passwordVisible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        trailingIcon = {
            TextButton(onClick = onTogglePasswordVisible) {
                Text(
                    stringResource(
                        if (passwordVisible) R.string.password_hide else R.string.password_show,
                    ),
                )
            }
        },
        supportingText = { Text(stringResource(R.string.forgot_new_password_helper)) },
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrect = false,
            imeAction = ImeAction.Done,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    PrimaryButton(
        label = stringResource(R.string.forgot_confirm_button),
        enabled = !submitting &&
            token.isNotBlank() &&
            newPassword.length >= MIN_RESET_PASSWORD_LENGTH,
        submitting = submitting,
        onClick = onSubmit,
    )
}

@Composable
private fun PrimaryButton(
    label: String,
    enabled: Boolean,
    submitting: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
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
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun errorMessage(error: ResetError): String = when (error) {
    ResetError.InvalidToken -> stringResource(R.string.forgot_error_invalid_token)
    ResetError.WeakPassword -> stringResource(R.string.forgot_error_weak_password)
    ResetError.RateLimited -> stringResource(R.string.forgot_error_rate_limited)
    ResetError.Network -> stringResource(R.string.forgot_error_network)
    ResetError.Generic -> stringResource(R.string.forgot_error_generic)
}
