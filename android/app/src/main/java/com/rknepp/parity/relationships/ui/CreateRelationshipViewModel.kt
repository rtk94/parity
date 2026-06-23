package com.rknepp.parity.relationships.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rknepp.parity.ServiceLocator
import com.rknepp.parity.network.ApiResult
import com.rknepp.parity.relationships.data.RelationshipRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CreateRelationshipState(
    val username: String = "",
    val currencyCode: String = "USD",
    val isSubmitting: Boolean = false,
    val error: String? = null,
    val success: Boolean = false,
)

class CreateRelationshipViewModel(
    private val repository: RelationshipRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(CreateRelationshipState())
    val state: StateFlow<CreateRelationshipState> = _state.asStateFlow()

    fun updateUsername(username: String) {
        _state.update { it.copy(username = username, error = null) }
    }

    fun updateCurrencyCode(currencyCode: String) {
        _state.update { it.copy(currencyCode = currencyCode.uppercase(), error = null) }
    }

    fun submit() {
        val current = _state.value
        if (current.isSubmitting) return
        if (current.username.isBlank()) {
            _state.update { it.copy(error = "Username is required") }
            return
        }
        if (current.currencyCode.length != 3) {
            _state.update { it.copy(error = "Currency code must be 3 letters") }
            return
        }

        _state.update { it.copy(isSubmitting = true, error = null) }

        viewModelScope.launch {
            when (val result = repository.create(current.username, current.currencyCode)) {
                is ApiResult.Success -> {
                    _state.update { it.copy(isSubmitting = false, success = true) }
                }
                is ApiResult.HttpFailure -> {
                    val msg = result.error?.message ?: "Failed to create relationship"
                    _state.update { it.copy(isSubmitting = false, error = msg) }
                }
                else -> {
                    _state.update { it.copy(isSubmitting = false, error = "Network error") }
                }
            }
        }
    }

    fun resetSuccess() {
        _state.update { it.copy(success = false) }
    }

    companion object {
        fun factory(locator: ServiceLocator): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                CreateRelationshipViewModel(locator.relationshipRepository)
            }
        }
    }
}
