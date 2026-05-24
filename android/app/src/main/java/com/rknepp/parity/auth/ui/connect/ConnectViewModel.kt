package com.rknepp.parity.auth.ui.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rknepp.parity.ServiceLocator
import com.rknepp.parity.auth.data.AuthRepository
import com.rknepp.parity.network.ApiResult
import com.rknepp.parity.storage.ServerUrlStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ConnectError {
    data object InvalidUrl : ConnectError
    data object BadScheme : ConnectError
    data object Network : ConnectError
    data class Http(val code: Int) : ConnectError
    data object Unexpected : ConnectError
}

data class ConnectState(
    val url: String = "",
    val submitting: Boolean = false,
    val error: ConnectError? = null,
)

class ConnectViewModel(
    private val serverUrlStore: ServerUrlStore,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ConnectState())
    val state: StateFlow<ConnectState> = _state.asStateFlow()

    fun onUrlChange(value: String) {
        _state.update { it.copy(url = value, error = null) }
    }

    fun submit(onConnected: () -> Unit) {
        val raw = _state.value.url.trim()
        val normalized = if (raw.endsWith("/")) raw.trimEnd('/') else raw

        if (normalized.isEmpty()) {
            _state.update { it.copy(error = ConnectError.InvalidUrl) }
            return
        }
        val scheme = normalized.substringBefore("://", missingDelimiterValue = "").lowercase()
        if (scheme != "http" && scheme != "https") {
            _state.update { it.copy(error = ConnectError.BadScheme) }
            return
        }
        val parsed = runCatching { android.net.Uri.parse(normalized) }.getOrNull()
        if (parsed == null || parsed.host.isNullOrBlank()) {
            _state.update { it.copy(error = ConnectError.InvalidUrl) }
            return
        }

        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            val result = authRepository.verifyServerUrl(normalized)
            when (result) {
                is ApiResult.Success -> {
                    if (result.data.status == "ok") {
                        serverUrlStore.set(normalized)
                        _state.update { it.copy(submitting = false) }
                        onConnected()
                    } else {
                        _state.update { it.copy(submitting = false, error = ConnectError.Unexpected) }
                    }
                }
                is ApiResult.HttpFailure -> _state.update {
                    it.copy(submitting = false, error = ConnectError.Http(result.code))
                }
                is ApiResult.NetworkFailure -> _state.update {
                    it.copy(submitting = false, error = ConnectError.Network)
                }
                is ApiResult.UnexpectedFailure -> _state.update {
                    it.copy(submitting = false, error = ConnectError.Unexpected)
                }
            }
        }
    }

    companion object {
        fun factory(locator: ServiceLocator): ViewModelProvider.Factory = viewModelFactory {
            initializer { ConnectViewModel(locator.serverUrlStore, locator.authRepository) }
        }
    }
}
