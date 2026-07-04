package com.rknepp.parity.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rknepp.parity.BuildConfig
import com.rknepp.parity.app.LocalServiceLocator
import com.rknepp.parity.ui.components.ConfirmActionDialog
import com.rknepp.parity.ui.components.InitialsAvatar
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val locator = LocalServiceLocator.current
    val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(locator))
    val state by vm.state.collectAsState()

    var displayNameInput by remember { mutableStateOf("") }
    var currentPasswordInput by remember { mutableStateOf("") }
    var newPasswordInput by remember { mutableStateOf("") }
    var showLogoutConfirm by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }

    // Sync the loaded profile into the edit field once.
    LaunchedEffect(state.displayName) {
        if (displayNameInput.isEmpty() && state.displayName.isNotEmpty()) {
            displayNameInput = state.displayName
        }
    }

    LaunchedEffect(state.profileSuccess) {
        if (state.profileSuccess) {
            delay(3000)
            vm.clearProfileSuccess()
        }
    }

    LaunchedEffect(state.passwordSuccess) {
        if (state.passwordSuccess) {
            currentPasswordInput = ""
            newPasswordInput = ""
            delay(3000)
            vm.clearPasswordSuccess()
        }
    }

    LaunchedEffect(state.adminMessage) {
        if (state.adminMessage != null) {
            delay(5000)
            vm.clearAdminMessage()
        }
    }

    if (showResetConfirm) {
        ResetLedgerDialog(
            onConfirm = {
                showResetConfirm = false
                vm.resetLedger()
            },
            onDismiss = { showResetConfirm = false },
        )
    }

    if (showLogoutConfirm) {
        ConfirmActionDialog(
            title = "Log out?",
            text = "Your data stays on the server — sign back in anytime.",
            confirmLabel = "Yes, log out",
            destructive = true,
            onConfirm = {
                showLogoutConfirm = false
                vm.logout()
            },
            onDismiss = { showLogoutConfirm = false },
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Profile
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    val shownName =
                        if (state.displayName.isBlank()) state.username else state.displayName
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        InitialsAvatar(name = shownName)
                        Column(modifier = Modifier.padding(start = 12.dp)) {
                            Text(
                                if (shownName.isBlank()) "…" else shownName,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                "@${state.username}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    OutlinedTextField(
                        value = displayNameInput,
                        onValueChange = { displayNameInput = it },
                        label = { Text("Display name") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                        singleLine = true,
                        enabled = !state.isSavingProfile,
                    )
                    if (state.profileError != null) {
                        Text(
                            text = state.profileError!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Button(
                        onClick = { vm.updateProfile(displayNameInput) },
                        enabled = !state.isSavingProfile &&
                            displayNameInput.isNotBlank() &&
                            displayNameInput.trim() != state.displayName,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                    ) {
                        when {
                            state.isSavingProfile -> CircularProgressIndicator(
                                modifier = Modifier.height(20.dp),
                                strokeWidth = 2.dp,
                            )
                            state.profileSuccess -> {
                                Icon(Icons.Default.Check, contentDescription = null)
                                Text("Saved", modifier = Modifier.padding(start = 8.dp))
                            }
                            else -> Text("Save profile")
                        }
                    }
                }
            }

            // Security
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Security", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = currentPasswordInput,
                        onValueChange = { currentPasswordInput = it },
                        label = { Text("Current password") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Next,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        singleLine = true,
                        enabled = !state.isSavingPassword,
                    )
                    OutlinedTextField(
                        value = newPasswordInput,
                        onValueChange = { newPasswordInput = it },
                        label = { Text("New password") },
                        supportingText = { Text("At least 8 characters") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        singleLine = true,
                        enabled = !state.isSavingPassword,
                    )
                    if (state.passwordError != null) {
                        Text(
                            text = state.passwordError!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Button(
                        onClick = { vm.changePassword(currentPasswordInput, newPasswordInput) },
                        enabled = !state.isSavingPassword &&
                            currentPasswordInput.isNotEmpty() &&
                            newPasswordInput.isNotEmpty(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                    ) {
                        when {
                            state.isSavingPassword -> CircularProgressIndicator(
                                modifier = Modifier.height(20.dp),
                                strokeWidth = 2.dp,
                            )
                            state.passwordSuccess -> {
                                Icon(Icons.Default.Check, contentDescription = null)
                                Text("Password changed", modifier = Modifier.padding(start = 8.dp))
                            }
                            else -> Text("Change password")
                        }
                    }
                }
            }

            if (state.isAdmin) {
                AdminPanel(
                    state = state,
                    onRefreshStats = { vm.refreshAdminStats() },
                    onCleanupTokens = { vm.cleanupTokens() },
                    onResetLedger = { showResetConfirm = true },
                )
            }

            // Log out
            OutlinedButton(
                onClick = { showLogoutConfirm = true },
                enabled = !state.isLoggingOut,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.isLoggingOut) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("Log out")
                }
            }

            Text(
                text = "Parity ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun AdminPanel(
    state: SettingsState,
    onRefreshStats: () -> Unit,
    onCleanupTokens: () -> Unit,
    onResetLedger: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                )
                Text(
                    "System administration",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp),
                )
                TextButton(onClick = onRefreshStats, enabled = !state.adminBusy) {
                    Text("Refresh")
                }
            }

            val stats = state.adminStats
            if (stats != null) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    AdminStatRow("Users", stats.users)
                    AdminStatRow("Relationships", stats.relationships)
                    AdminStatRow("Expenses", stats.expenses)
                    AdminStatRow("Payments", stats.payments)
                    AdminStatRow("Comments", stats.comments)
                    AdminStatRow("Active sessions", stats.active_tokens)
                    AdminStatRow("Audit entries", stats.audit_entries)
                }
            } else {
                Text(
                    "Stats unavailable.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            if (state.adminMessage != null) {
                Text(
                    text = state.adminMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            OutlinedButton(
                onClick = onCleanupTokens,
                enabled = !state.adminBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            ) {
                Text("Clean up expired tokens")
            }

            OutlinedButton(
                onClick = onResetLedger,
                enabled = !state.adminBusy,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                if (state.adminBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("Reset ledger…")
                }
            }
        }
    }
}

@Composable
private fun AdminStatRow(label: String, value: Long) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value.toString(), style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Type-to-confirm gate for the ledger reset. The confirm button stays
 * disabled until the operator types RESET, mirroring the backend's own
 * confirmation-phrase requirement.
 */
@Composable
private fun ResetLedgerDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var typed by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reset the ledger?") },
        text = {
            Column {
                Text(
                    "This permanently erases every expense, payment, and comment " +
                        "for every user on this server. Accounts and relationships " +
                        "survive; balances return to zero. There is no undo.",
                )
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text("Type RESET to confirm") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = typed.trim() == "RESET",
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text("Erase everything")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
