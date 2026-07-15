package com.rknepp.parity.auth.ui.forgot

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

/** Minimum new-password length; mirrors the backend's MIN_PASSWORD_LENGTH. */
const val MIN_RESET_PASSWORD_LENGTH = 8

/** The two steps of the paste-token reset flow. */
enum class ResetPhase {
    /** Enter the account email to request a token. */
    Request,

    /** Paste the emailed token and choose a new password. */
    Confirm,
}

sealed interface ResetError {
    data object InvalidToken : ResetError
    data object WeakPassword : ResetError
    data object RateLimited : ResetError
    data object Network : ResetError
    data object Generic : ResetError
}

data class ForgotPasswordState(
    val phase: ResetPhase = ResetPhase.Request,
    val email: String = "",
    val token: String = "",
    val newPassword: String = "",
    val submitting: Boolean = false,
    val error: ResetError? = null,
)

class ForgotPasswordViewModel(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ForgotPasswordState())
    val state: StateFlow<ForgotPasswordState> = _state.asStateFlow()

    fun onEmailChange(value: String) {
        _state.update { it.copy(email = value, error = null) }
    }

    fun onTokenChange(value: String) {
        _state.update { it.copy(token = value, error = null) }
    }

    fun onNewPasswordChange(value: String) {
        _state.update { it.copy(newPassword = value, error = null) }
    }

    /**
     * Requests a reset token for the entered email. The backend is
     * enumeration-resistant (always 204), so any non-error response
     * advances to the confirm step — the UI never reveals whether the
     * address was registered.
     */
    fun requestReset() {
        val s = _state.value
        if (s.email.isBlank() || s.submitting) return

        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            when (val result = authRepository.requestPasswordReset(s.email.trim())) {
                is ApiResult.Success -> _state.update {
                    it.copy(submitting = false, phase = ResetPhase.Confirm, error = null)
                }
                is ApiResult.HttpFailure -> _state.update {
                    it.copy(submitting = false, error = mapHttpError(result.code, null))
                }
                is ApiResult.NetworkFailure -> _state.update {
                    it.copy(submitting = false, error = ResetError.Network)
                }
                is ApiResult.UnexpectedFailure -> _state.update {
                    it.copy(submitting = false, error = ResetError.Generic)
                }
            }
        }
    }

    /**
     * Consumes the pasted token and sets the new password. On success the
     * backend revokes every session, so [onSuccess] should route back to
     * the login screen where the user signs in fresh.
     */
    fun confirmReset(onSuccess: () -> Unit) {
        val s = _state.value
        if (s.token.isBlank() || s.newPassword.length < MIN_RESET_PASSWORD_LENGTH || s.submitting) {
            return
        }

        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            val result = authRepository.confirmPasswordReset(s.token.trim(), s.newPassword)
            when (result) {
                is ApiResult.Success -> {
                    _state.update { it.copy(submitting = false) }
                    onSuccess()
                }
                is ApiResult.HttpFailure -> _state.update {
                    it.copy(submitting = false, error = mapHttpError(result.code, result.error?.code))
                }
                is ApiResult.NetworkFailure -> _state.update {
                    it.copy(submitting = false, error = ResetError.Network)
                }
                is ApiResult.UnexpectedFailure -> _state.update {
                    it.copy(submitting = false, error = ResetError.Generic)
                }
            }
        }
    }

    /** Return to the request step, e.g. to re-send with a corrected email. */
    fun backToRequest() {
        _state.update {
            it.copy(phase = ResetPhase.Request, token = "", newPassword = "", error = null)
        }
    }

    private fun mapHttpError(code: Int, errorCode: String?): ResetError = when {
        code == 429 -> ResetError.RateLimited
        code == 422 && errorCode == "weak_password" -> ResetError.WeakPassword
        // invalid_token, and any other 422, read as a bad/expired token.
        code == 422 -> ResetError.InvalidToken
        else -> ResetError.Generic
    }

    companion object {
        fun factory(locator: ServiceLocator): ViewModelProvider.Factory = viewModelFactory {
            initializer { ForgotPasswordViewModel(locator.authRepository) }
        }
    }
}
