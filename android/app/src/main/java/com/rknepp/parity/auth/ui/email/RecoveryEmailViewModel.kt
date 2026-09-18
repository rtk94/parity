package com.rknepp.parity.auth.ui.email

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rknepp.parity.ServiceLocator
import com.rknepp.parity.auth.data.AuthRepository
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

/** What the gate should render while it works out the account's state. */
enum class GatePhase {
    /** Fetching /auth/me to find out whether an address is on file. */
    Loading,

    /** Could not reach the server; the gate cannot safely decide. */
    Failed,

    /** No address on file — the blocking prompt is shown. */
    Prompting,

    /** An address is on file; the app proper may render. */
    Satisfied,
}

sealed interface RecoveryEmailError {
    data object Invalid : RecoveryEmailError
    data object Taken : RecoveryEmailError
    data object Network : RecoveryEmailError
    data object Generic : RecoveryEmailError
}

data class RecoveryEmailState(
    val phase: GatePhase = GatePhase.Loading,
    val email: String = "",
    val displayName: String = "",
    val submitting: Boolean = false,
    val error: RecoveryEmailError? = null,
)

/**
 * Backs the recovery-email gate.
 *
 * Accounts created before email was mandatory still have none on file,
 * and those are exactly the accounts that cannot be recovered. The gate
 * resolves that on the next login rather than leaving it to a banner the
 * user can ignore.
 *
 * On a failed fetch the gate shows a retry rather than falling through:
 * treating "we don't know" as "an address is on file" would let the very
 * accounts this exists for slip past on a flaky connection.
 */
class RecoveryEmailViewModel(
    private val meRepository: MeRepository,
    private val authRepository: AuthRepository,
    private val authEventBus: AuthEventBus,
    private val pushRegistrar: PushRegistrar,
) : ViewModel() {

    private val _state = MutableStateFlow(RecoveryEmailState())
    val state: StateFlow<RecoveryEmailState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(phase = GatePhase.Loading, error = null) }
        viewModelScope.launch {
            when (val result = meRepository.fetchMe()) {
                is ApiResult.Success -> {
                    val existing = result.data.email
                    _state.update {
                        it.copy(
                            phase = if (existing.isNullOrBlank()) {
                                GatePhase.Prompting
                            } else {
                                GatePhase.Satisfied
                            },
                            displayName = result.data.displayName,
                        )
                    }
                }
                // A 401 is handled globally by the auth interceptor, which
                // routes to login; anything else leaves the gate unable to
                // decide, so it offers a retry.
                else -> _state.update { it.copy(phase = GatePhase.Failed) }
            }
        }
    }

    fun onEmailChange(value: String) {
        _state.update { it.copy(email = value, error = null) }
    }

    fun save() {
        val s = _state.value
        if (s.submitting || s.email.isBlank()) return

        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            // display_name is required by the endpoint and unchanged here;
            // sending the current value keeps this a pure email update.
            val request = UpdateProfileRequest(s.displayName, s.email.trim())
            when (val result = meRepository.updateProfile(request)) {
                is ApiResult.Success -> _state.update {
                    it.copy(submitting = false, phase = GatePhase.Satisfied)
                }
                is ApiResult.HttpFailure -> {
                    val mapped = when {
                        result.code == 409 -> RecoveryEmailError.Taken
                        result.code == 422 -> RecoveryEmailError.Invalid
                        else -> RecoveryEmailError.Generic
                    }
                    _state.update { it.copy(submitting = false, error = mapped) }
                }
                is ApiResult.NetworkFailure -> _state.update {
                    it.copy(submitting = false, error = RecoveryEmailError.Network)
                }
                is ApiResult.UnexpectedFailure -> _state.update {
                    it.copy(submitting = false, error = RecoveryEmailError.Generic)
                }
            }
        }
    }

    /**
     * Escape hatch. The gate is otherwise unskippable, so without this a
     * user who cannot supply a usable address — a shared account, a typo
     * in a taken address — would be stuck with no way out of the app.
     * Mirrors the Settings logout: drop the push registration while the
     * token is still valid, then revoke and route to login.
     */
    fun signOut() {
        if (_state.value.submitting) return
        _state.update { it.copy(submitting = true) }
        viewModelScope.launch {
            pushRegistrar.unregisterCurrentDevice()
            authRepository.logout()
            _state.update { it.copy(submitting = false) }
            authEventBus.tryEmit(AuthEvent.LoggedOut)
        }
    }

    companion object {
        fun factory(locator: ServiceLocator): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                RecoveryEmailViewModel(
                    locator.meRepository,
                    locator.authRepository,
                    locator.authEventBus,
                    locator.pushRegistrar,
                )
            }
        }
    }
}
