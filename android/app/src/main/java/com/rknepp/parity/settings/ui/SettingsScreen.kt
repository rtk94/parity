package com.rknepp.parity.settings.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rknepp.parity.BuildConfig
import com.rknepp.parity.app.LocalServiceLocator
import com.rknepp.parity.ui.components.ConfirmActionDialog
import com.rknepp.parity.ui.components.InitialsAvatar
import com.rknepp.parity.ui.theme.PillShape
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val locator = LocalServiceLocator.current
    val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(locator))
    val state by vm.state.collectAsState()

    var displayNameInput by remember { mutableStateOf("") }
    var emailInput by remember { mutableStateOf("") }
    var currentPasswordInput by remember { mutableStateOf("") }
    var newPasswordInput by remember { mutableStateOf("") }
    var showLogoutConfirm by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // Data export: fetch JSON into state, then hand it to the system
    // file-save dialog (Storage Access Framework — no permissions needed).
    val context = LocalContext.current
    var pendingExport by remember { mutableStateOf<String?>(null) }
    val saveExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val data = pendingExport
        pendingExport = null
        if (uri != null && data != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(data.toByteArray())
                }
            }
        }
        vm.clearExportReady()
    }

    LaunchedEffect(state.exportReady) {
        val ready = state.exportReady
        if (ready != null) {
            pendingExport = ready
            saveExportLauncher.launch("parity-export.json")
        }
    }

    LaunchedEffect(state.exportError) {
        if (state.exportError != null) {
            delay(5000)
            vm.clearExportError()
        }
    }

    // Sync the loaded profile into the edit fields once.
    LaunchedEffect(state.displayName) {
        if (displayNameInput.isEmpty() && state.displayName.isNotEmpty()) {
            displayNameInput = state.displayName
        }
    }
    LaunchedEffect(state.email) {
        if (emailInput.isEmpty() && state.email.isNotEmpty()) {
            emailInput = state.email
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

    if (showDeleteConfirm) {
        DeleteAccountDialog(
            isDeleting = state.isDeleting,
            error = state.deleteError,
            onConfirm = { password -> vm.deleteAccount(password) },
            onDismiss = {
                if (!state.isDeleting) {
                    showDeleteConfirm = false
                    vm.clearDeleteError()
                }
            },
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
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            // Profile
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                LabelCaps("Profile", MaterialTheme.colorScheme.onSurfaceVariant)
                // Reserve the identity-row height while the profile loads so
                // the layout doesn't jump — and so we never flash a "?"
                // avatar or "…" placeholder name.
                if (state.profileLoaded) {
                    val shownName =
                        if (state.displayName.isBlank()) state.username else state.displayName
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        InitialsAvatar(name = shownName)
                        Column(modifier = Modifier.padding(start = 12.dp)) {
                            Text(
                                shownName,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                "@${state.username}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.height(44.dp))
                }

                OutlinedTextField(
                    value = displayNameInput,
                    onValueChange = { displayNameInput = it },
                    label = { Text("Display name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !state.isSavingProfile,
                )
                OutlinedTextField(
                    value = emailInput,
                    onValueChange = { emailInput = it },
                    label = { Text("Email") },
                    supportingText = { Text("For password resets. Leave blank to remove.") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !state.isSavingProfile,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Done,
                    ),
                )
                if (state.profileError != null) {
                    Text(
                        text = state.profileError!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Button(
                    onClick = { vm.updateProfile(displayNameInput, emailInput) },
                    enabled = !state.isSavingProfile &&
                        displayNameInput.isNotBlank() &&
                        (
                            displayNameInput.trim() != state.displayName ||
                                emailInput.trim() != state.email
                            ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = PillShape,
                    elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
                ) {
                    when {
                        state.isSavingProfile -> CircularProgressIndicator(
                            modifier = Modifier.padding(2.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        state.profileSuccess -> {
                            Icon(Icons.Default.Check, contentDescription = null)
                            Text(
                                "Saved",
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                        else -> Text("Save profile", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Security
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LabelCaps("Security", MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = currentPasswordInput,
                    onValueChange = { currentPasswordInput = it },
                    label = { Text("Current password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
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
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !state.isSavingPassword,
                )
                if (state.passwordError != null) {
                    Text(
                        text = state.passwordError!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Button(
                    onClick = { vm.changePassword(currentPasswordInput, newPasswordInput) },
                    enabled = !state.isSavingPassword &&
                        currentPasswordInput.isNotEmpty() &&
                        newPasswordInput.isNotEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = PillShape,
                    elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
                ) {
                    when {
                        state.isSavingPassword -> CircularProgressIndicator(
                            modifier = Modifier.padding(2.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        state.passwordSuccess -> {
                            Icon(Icons.Default.Check, contentDescription = null)
                            Text(
                                "Password changed",
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                        else -> Text(
                            "Change password",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }

            if (state.isAdmin) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                AdminPanel(
                    state = state,
                    onRefreshStats = { vm.refreshAdminStats() },
                    onCleanupTokens = { vm.cleanupTokens() },
                    onResetLedger = { showResetConfirm = true },
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Data & privacy
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LabelCaps("Data & privacy", MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "Download a copy of everything in your account — your " +
                        "relationships, expenses, payments, and comments — as JSON.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.exportError != null) {
                    Text(
                        text = state.exportError!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedButton(
                    onClick = { vm.exportData() },
                    enabled = !state.isExporting,
                    shape = PillShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) {
                    if (state.isExporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(2.dp),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Text("Download my data", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Danger zone
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LabelCaps("Danger zone", MaterialTheme.colorScheme.error)
                Text(
                    "Deleting your account removes your name, login, and sessions. " +
                        "Shared entries stay on your partner's ledger but show as " +
                        "\"Deleted user.\" This cannot be undone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { showDeleteConfirm = true },
                    shape = PillShape,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) {
                    Text("Delete account…", style = MaterialTheme.typography.labelLarge)
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Log out
            OutlinedButton(
                onClick = { showLogoutConfirm = true },
                enabled = !state.isLoggingOut,
                shape = PillShape,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                if (state.isLoggingOut) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(2.dp),
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Text("Log out", style = MaterialTheme.typography.labelLarge)
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
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
            )
            LabelCaps(
                "System administration",
                MaterialTheme.colorScheme.onSurfaceVariant,
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
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
            )
        }

        if (state.adminMessage != null) {
            Text(
                text = state.adminMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        OutlinedButton(
            onClick = onCleanupTokens,
            enabled = !state.adminBusy,
            shape = PillShape,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Text("Clean up expired tokens", style = MaterialTheme.typography.labelLarge)
        }

        OutlinedButton(
            onClick = onResetLedger,
            enabled = !state.adminBusy,
            shape = PillShape,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            if (state.adminBusy) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(2.dp),
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Text("Reset ledger…", style = MaterialTheme.typography.labelLarge)
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

@Composable
private fun LabelCaps(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = modifier,
    )
}

/**
 * Password-confirmed account deletion. The confirm button stays disabled
 * until a password is entered; the backend re-verifies it and returns 403
 * on a mismatch, which surfaces here as [error].
 */
@Composable
private fun DeleteAccountDialog(
    isDeleting: Boolean,
    error: String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete your account?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "This permanently removes your name, login, and sessions. " +
                        "Your shared entries stay on your partner's ledger but " +
                        "will show as \"Deleted user.\" There is no undo.",
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Confirm your password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    singleLine = true,
                    enabled = !isDeleting,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(password) },
                enabled = !isDeleting && password.isNotEmpty(),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text(if (isDeleting) "Deleting…" else "Delete account")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isDeleting) {
                Text("Cancel")
            }
        },
    )
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
