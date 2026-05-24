package com.rknepp.parity.auth.ui.register

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rknepp.parity.ServiceLocator
import com.rknepp.parity.auth.data.AuthRepository
import com.rknepp.parity.network.ApiResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface RegisterError {
    data object UsernameTaken : RegisterError
    data object Network : RegisterError
    data class ServerMessage(val message: String) : RegisterError
    data object Generic : RegisterError
}

data class RegisterState(
    val username: String = "",
    val password: String = "",
    val displayName: String = "",
    val submitting: Boolean = false,
    val error: RegisterError? = null,
)

class RegisterViewModel(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(RegisterState())
    val state: StateFlow<RegisterState> = _state.asStateFlow()

    fun onUsernameChange(value: String) {
        _state.update { it.copy(username = value, error = null) }
    }
    fun onPasswordChange(value: String) {
        _state.update { it.copy(password = value, error = null) }
    }
    fun onDisplayNameChange(value: String) {
        _state.update { it.copy(displayName = value, error = null) }
    }

    fun submit(onRegistered: (username: String) -> Unit) {
        val s = _state.value
        if (s.submitting) return
        if (s.username.isBlank() || s.password.isEmpty() || s.displayName.isBlank()) return

        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            val result = authRepository.register(
                username = s.username.trim(),
                password = s.password,
                displayName = s.displayName.trim(),
            )
            when (result) {
                is ApiResult.Success -> {
                    _state.update { RegisterState() }
                    onRegistered(result.data.username)
                }
                is ApiResult.HttpFailure -> {
                    val mapped = when {
                        result.code == 409 && result.error?.code == "username_taken" ->
                            RegisterError.UsernameTaken
                        result.code == 422 && result.error != null ->
                            RegisterError.ServerMessage(result.error.message)
                        else -> RegisterError.Generic
                    }
                    _state.update { it.copy(submitting = false, error = mapped) }
                }
                is ApiResult.NetworkFailure -> _state.update {
                    it.copy(submitting = false, error = RegisterError.Network)
                }
                is ApiResult.UnexpectedFailure -> _state.update {
                    it.copy(submitting = false, error = RegisterError.Generic)
                }
            }
        }
    }

    companion object {
        fun factory(locator: ServiceLocator): ViewModelProvider.Factory = viewModelFactory {
            initializer { RegisterViewModel(locator.authRepository) }
        }
    }
}
