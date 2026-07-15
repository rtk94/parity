package com.rknepp.parity.settings.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rknepp.parity.ServiceLocator
import com.rknepp.parity.admin.data.AdminRepository
import com.rknepp.parity.admin.data.dto.AdminStatsDto
import com.rknepp.parity.auth.data.AuthRepository
import com.rknepp.parity.auth.data.dto.ChangePasswordRequest
import com.rknepp.parity.auth.data.dto.UpdateProfileRequest
import com.rknepp.parity.auth.events.AuthEvent
import com.rknepp.parity.auth.events.AuthEventBus
import com.rknepp.parity.home.data.MeRepository
import com.rknepp.parity.network.ApiResult
import com.rknepp.parity.push.PushRegistrar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

data class SettingsState(
    val profileLoaded: Boolean = false,
    val username: String = "",
    val displayName: String = "",
    // Recovery email; empty when the account has none set.
    val email: String = "",
    val isSavingProfile: Boolean = false,
    val profileError: String? = null,
    val profileSuccess: Boolean = false,
    val isSavingPassword: Boolean = false,
    val passwordError: String? = null,
    val passwordSuccess: Boolean = false,
    val isLoggingOut: Boolean = false,
    // Data export. `exportReady` carries the pretty-printed JSON for the
    // UI to hand to the file-save dialog, then it's cleared.
    val isExporting: Boolean = false,
    val exportError: String? = null,
    val exportReady: String? = null,
    // Account deletion.
    val isDeleting: Boolean = false,
    val deleteError: String? = null,
    // System administration; populated only when the signed-in user
    // is the admin account.
    val isAdmin: Boolean = false,
    val adminStats: AdminStatsDto? = null,
    val adminBusy: Boolean = false,
    val adminMessage: String? = null,
)

class SettingsViewModel(
    private val meRepository: MeRepository,
    private val authRepository: AuthRepository,
    private val adminRepository: AdminRepository,
    private val authEventBus: AuthEventBus,
    private val pushRegistrar: PushRegistrar,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    // Pretty-printed so the saved export file is human-readable.
    private val exportJson = Json { prettyPrint = true }

    init {
        loadProfile()
    }

    private fun loadProfile() {
        viewModelScope.launch {
            val result = meRepository.fetchMe()
            if (result is ApiResult.Success) {
                _state.update {
                    it.copy(
                        profileLoaded = true,
                        username = result.data.username,
                        displayName = result.data.displayName,
                        email = result.data.email.orEmpty(),
                        isAdmin = result.data.isAdmin,
                    )
                }
                if (result.data.isAdmin) refreshAdminStats()
            }
        }
    }

    fun updateProfile(displayName: String, email: String) {
        if (displayName.isBlank()) {
            _state.update { it.copy(profileError = "Display name cannot be empty") }
            return
        }
        _state.update { it.copy(isSavingProfile = true, profileError = null, profileSuccess = false) }
        viewModelScope.launch {
            // Email is sent as-is (trimmed): a blank string clears the
            // recovery address server-side, a value sets it.
            val request = UpdateProfileRequest(displayName.trim(), email.trim())
            when (val result = meRepository.updateProfile(request)) {
                is ApiResult.Success -> _state.update {
                    it.copy(
                        isSavingProfile = false,
                        profileSuccess = true,
                        displayName = result.data.displayName,
                        email = result.data.email.orEmpty(),
                    )
                }
                is ApiResult.HttpFailure -> _state.update {
                    it.copy(
                        isSavingProfile = false,
                        profileError = result.error?.message ?: "Failed to update profile",
                    )
                }
                else -> _state.update {
                    it.copy(
                        isSavingProfile = false,
                        profileError = "Failed to update profile. Check your connection.",
                    )
                }
            }
        }
    }

    fun changePassword(current: String, new: String) {
        if (current.isBlank() || new.isBlank()) {
            _state.update { it.copy(passwordError = "Passwords cannot be empty") }
            return
        }
        if (new.length < 8) {
            _state.update { it.copy(passwordError = "New password must be at least 8 characters") }
            return
        }
        _state.update { it.copy(isSavingPassword = true, passwordError = null, passwordSuccess = false) }
        viewModelScope.launch {
            when (val result = meRepository.changePassword(ChangePasswordRequest(current, new))) {
                is ApiResult.Success -> _state.update {
                    it.copy(isSavingPassword = false, passwordSuccess = true)
                }
                is ApiResult.HttpFailure -> _state.update {
                    it.copy(
                        isSavingPassword = false,
                        passwordError = result.error?.message
                            ?: "Failed to change password. Check your current password.",
                    )
                }
                else -> _state.update {
                    it.copy(
                        isSavingPassword = false,
                        passwordError = "Failed to change password. Check your connection.",
                    )
                }
            }
        }
    }

    fun clearProfileSuccess() {
        _state.update { it.copy(profileSuccess = false) }
    }

    fun clearPasswordSuccess() {
        _state.update { it.copy(passwordSuccess = false) }
    }

    fun logout() {
        if (_state.value.isLoggingOut) return
        _state.update { it.copy(isLoggingOut = true) }
        viewModelScope.launch {
            // Best-effort: drop this device's push registration first,
            // while the session token is still valid to authorize it.
            pushRegistrar.unregisterCurrentDevice()
            // Revokes the server-side token and clears the local store
            // regardless of the network outcome, then routes to login.
            authRepository.logout()
            _state.update { it.copy(isLoggingOut = false) }
            authEventBus.tryEmit(AuthEvent.LoggedOut)
        }
    }

    // --- Data export ----------------------------------------------------

    fun exportData() {
        if (_state.value.isExporting) return
        _state.update { it.copy(isExporting = true, exportError = null) }
        viewModelScope.launch {
            when (val result = meRepository.exportData()) {
                is ApiResult.Success -> _state.update {
                    it.copy(
                        isExporting = false,
                        exportReady = exportJson.encodeToString(JsonElement.serializer(), result.data),
                    )
                }
                is ApiResult.HttpFailure -> _state.update {
                    it.copy(
                        isExporting = false,
                        exportError = result.error?.message ?: "Couldn't export your data.",
                    )
                }
                else -> _state.update {
                    it.copy(
                        isExporting = false,
                        exportError = "Couldn't export your data. Check your connection.",
                    )
                }
            }
        }
    }

    /** Called by the UI once the export JSON has been handed to the save dialog. */
    fun clearExportReady() {
        _state.update { it.copy(exportReady = null) }
    }

    fun clearExportError() {
        _state.update { it.copy(exportError = null) }
    }

    // --- Account deletion -----------------------------------------------

    fun deleteAccount(password: String) {
        if (_state.value.isDeleting) return
        if (password.isEmpty()) {
            _state.update { it.copy(deleteError = "Enter your password to confirm.") }
            return
        }
        _state.update { it.copy(isDeleting = true, deleteError = null) }
        viewModelScope.launch {
            when (val result = authRepository.deleteAccount(password)) {
                is ApiResult.Success -> {
                    _state.update { it.copy(isDeleting = false) }
                    // Token is already cleared; route to login (no
                    // "session expired" banner — this was deliberate).
                    authEventBus.tryEmit(AuthEvent.LoggedOut)
                }
                is ApiResult.HttpFailure -> _state.update {
                    it.copy(
                        isDeleting = false,
                        deleteError = result.error?.message ?: "Couldn't delete your account.",
                    )
                }
                else -> _state.update {
                    it.copy(
                        isDeleting = false,
                        deleteError = "Couldn't delete your account. Check your connection.",
                    )
                }
            }
        }
    }

    fun clearDeleteError() {
        _state.update { it.copy(deleteError = null) }
    }

    // --- System administration -----------------------------------------

    fun refreshAdminStats() {
        viewModelScope.launch {
            when (val result = adminRepository.stats()) {
                is ApiResult.Success -> _state.update { it.copy(adminStats = result.data) }
                else -> _state.update {
                    it.copy(adminMessage = "Couldn't load system stats.")
                }
            }
        }
    }

    fun cleanupTokens() {
        if (_state.value.adminBusy) return
        _state.update { it.copy(adminBusy = true, adminMessage = null) }
        viewModelScope.launch {
            when (val result = adminRepository.cleanupTokens()) {
                is ApiResult.Success -> {
                    _state.update {
                        it.copy(
                            adminBusy = false,
                            adminMessage =
                                "Removed ${result.data.deleted_tokens} expired/revoked tokens.",
                        )
                    }
                    refreshAdminStats()
                }
                else -> _state.update {
                    it.copy(adminBusy = false, adminMessage = "Token cleanup failed. Try again.")
                }
            }
        }
    }

    fun resetLedger() {
        if (_state.value.adminBusy) return
        _state.update { it.copy(adminBusy = true, adminMessage = null) }
        viewModelScope.launch {
            when (val result = adminRepository.resetLedger()) {
                is ApiResult.Success -> {
                    val d = result.data.deleted
                    _state.update {
                        it.copy(
                            adminBusy = false,
                            adminMessage = "Ledger reset: erased ${d.expenses} expenses, " +
                                "${d.payments} payments, ${d.comments} comments.",
                        )
                    }
                    refreshAdminStats()
                }
                is ApiResult.HttpFailure -> _state.update {
                    it.copy(
                        adminBusy = false,
                        adminMessage = result.error?.message ?: "Ledger reset failed.",
                    )
                }
                else -> _state.update {
                    it.copy(
                        adminBusy = false,
                        adminMessage = "Ledger reset failed. Check your connection.",
                    )
                }
            }
        }
    }

    fun clearAdminMessage() {
        _state.update { it.copy(adminMessage = null) }
    }

    companion object {
        fun factory(locator: ServiceLocator): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    locator.meRepository,
                    locator.authRepository,
                    locator.adminRepository,
                    locator.authEventBus,
                    locator.pushRegistrar,
                )
            }
        }
    }
}
