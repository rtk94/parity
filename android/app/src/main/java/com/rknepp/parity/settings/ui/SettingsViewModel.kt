package com.rknepp.parity.settings.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rknepp.parity.ServiceLocator
import com.rknepp.parity.auth.data.AuthRepository
import com.rknepp.parity.auth.data.dto.ChangePasswordRequest
import com.rknepp.parity.auth.data.dto.UpdateProfileRequest
import com.rknepp.parity.auth.events.AuthEvent
import com.rknepp.parity.auth.events.AuthEventBus
import com.rknepp.parity.home.data.MeRepository
import com.rknepp.parity.network.ApiResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsState(
    val username: String = "",
    val displayName: String = "",
    val isSavingProfile: Boolean = false,
    val profileError: String? = null,
    val profileSuccess: Boolean = false,
    val isSavingPassword: Boolean = false,
    val passwordError: String? = null,
    val passwordSuccess: Boolean = false,
    val isLoggingOut: Boolean = false,
)

class SettingsViewModel(
    private val meRepository: MeRepository,
    private val authRepository: AuthRepository,
    private val authEventBus: AuthEventBus,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    init {
        loadProfile()
    }

    private fun loadProfile() {
        viewModelScope.launch {
            val result = meRepository.fetchMe()
            if (result is ApiResult.Success) {
                _state.update {
                    it.copy(
                        username = result.data.username,
                        displayName = result.data.displayName,
                    )
                }
            }
        }
    }

    fun updateProfile(displayName: String) {
        if (displayName.isBlank()) {
            _state.update { it.copy(profileError = "Display name cannot be empty") }
            return
        }
        _state.update { it.copy(isSavingProfile = true, profileError = null, profileSuccess = false) }
        viewModelScope.launch {
            when (val result = meRepository.updateProfile(UpdateProfileRequest(displayName.trim()))) {
                is ApiResult.Success -> _state.update {
                    it.copy(
                        isSavingProfile = false,
                        profileSuccess = true,
                        displayName = result.data.displayName,
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
            // Revokes the server-side token and clears the local store
            // regardless of the network outcome, then routes to login.
            authRepository.logout()
            _state.update { it.copy(isLoggingOut = false) }
            authEventBus.tryEmit(AuthEvent.LoggedOut)
        }
    }

    companion object {
        fun factory(locator: ServiceLocator): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    locator.meRepository,
                    locator.authRepository,
                    locator.authEventBus,
                )
            }
        }
    }
}
