package com.rknepp.parity.auth.ui.login

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

sealed interface LoginError {
    data object InvalidCredentials : LoginError
    data object RateLimited : LoginError
    data object Network : LoginError
    data object Generic : LoginError
}

data class LoginState(
    val username: String = "",
    val password: String = "",
    val submitting: Boolean = false,
    val error: LoginError? = null,
)

class LoginViewModel(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(LoginState())
    val state: StateFlow<LoginState> = _state.asStateFlow()

    fun onUsernameChange(value: String) {
        _state.update { it.copy(username = value, error = null) }
    }

    fun onPasswordChange(value: String) {
        _state.update { it.copy(password = value, error = null) }
    }

    fun prefillUsername(value: String) {
        if (value.isNotBlank() && _state.value.username.isBlank()) {
            _state.update { it.copy(username = value) }
        }
    }

    fun submit(onSuccess: () -> Unit) {
        val s = _state.value
        if (s.username.isBlank() || s.password.isEmpty() || s.submitting) return

        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            val result = authRepository.login(s.username.trim(), s.password)
            when (result) {
                is ApiResult.Success -> {
                    _state.update { LoginState() }
                    onSuccess()
                }
                is ApiResult.HttpFailure -> {
                    val mapped = when (result.code) {
                        401 -> LoginError.InvalidCredentials
                        429 -> LoginError.RateLimited
                        else -> LoginError.Generic
                    }
                    _state.update { it.copy(submitting = false, error = mapped) }
                }
                is ApiResult.NetworkFailure -> _state.update {
                    it.copy(submitting = false, error = LoginError.Network)
                }
                is ApiResult.UnexpectedFailure -> _state.update {
                    it.copy(submitting = false, error = LoginError.Generic)
                }
            }
        }
    }

    companion object {
        fun factory(locator: ServiceLocator): ViewModelProvider.Factory = viewModelFactory {
            initializer { LoginViewModel(locator.authRepository) }
        }
    }
}
