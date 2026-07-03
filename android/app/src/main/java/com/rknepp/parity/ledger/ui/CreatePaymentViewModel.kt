package com.rknepp.parity.ledger.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rknepp.parity.ServiceLocator
import com.rknepp.parity.home.data.MeRepository
import com.rknepp.parity.ledger.data.LedgerRepository
import com.rknepp.parity.ledger.data.dto.CreatePaymentRequest
import com.rknepp.parity.network.ApiResult
import com.rknepp.parity.relationships.data.RelationshipRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CreatePaymentState(
    val amountInput: String = "",
    val description: String = "",
    val isSubmitting: Boolean = false,
    val isReady: Boolean = false,
    val error: String? = null,
    val success: Boolean = false,
    val myId: Long = 0,
    val counterpartyId: Long = 0,
    val counterpartyName: String = "",
    val currencyCode: String = "",
    val isUserPaying: Boolean = true,
) {
    val amountCents: Long get() = parseAmountToCents(amountInput) ?: 0L
}

class CreatePaymentViewModel(
    private val relationshipId: Long,
    private val ledgerRepository: LedgerRepository,
    private val relationshipRepository: RelationshipRepository,
    private val meRepository: MeRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(CreatePaymentState())
    val state: StateFlow<CreatePaymentState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val meRes = meRepository.fetchMe()
            val relRes = relationshipRepository.get(relationshipId)
            if (meRes is ApiResult.Success && relRes is ApiResult.Success) {
                val myId = meRes.data.id
                val rel = relRes.data
                val other = if (rel.invitingUser.id == myId) rel.invitedUser else rel.invitingUser
                _state.update {
                    it.copy(
                        isReady = true,
                        myId = myId,
                        counterpartyId = other.id,
                        counterpartyName = other.displayName,
                        currencyCode = rel.currencyCode,
                    )
                }
            } else {
                _state.update { it.copy(error = "Failed to load relationship context") }
            }
        }
    }

    fun updateAmount(amount: String) {
        _state.update { it.copy(amountInput = amount, error = null) }
    }

    fun updateDescription(description: String) {
        _state.update { it.copy(description = description, error = null) }
    }

    fun setIsUserPaying(isUserPaying: Boolean) {
        _state.update { it.copy(isUserPaying = isUserPaying, error = null) }
    }

    fun submit() {
        val current = _state.value
        if (current.isSubmitting || !current.isReady) return

        val amountCents = parseAmountToCents(current.amountInput)
        if (amountCents == null || amountCents <= 0) {
            _state.update { it.copy(error = "Enter a valid amount") }
            return
        }
        if (current.description.isBlank()) {
            _state.update { it.copy(error = "Description is required") }
            return
        }

        _state.update { it.copy(isSubmitting = true, error = null) }

        val request = CreatePaymentRequest(
            relationship_id = relationshipId,
            from_user_id = if (current.isUserPaying) current.myId else current.counterpartyId,
            to_user_id = if (current.isUserPaying) current.counterpartyId else current.myId,
            amount_cents = amountCents,
            description = current.description.trim(),
        )

        viewModelScope.launch {
            when (val result = ledgerRepository.createPayment(request)) {
                is ApiResult.Success -> {
                    _state.update { it.copy(isSubmitting = false, success = true) }
                }
                is ApiResult.HttpFailure -> {
                    val msg = result.error?.message ?: "Failed to create payment"
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
        fun factory(locator: ServiceLocator, relationshipId: Long): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    CreatePaymentViewModel(
                        relationshipId,
                        locator.ledgerRepository,
                        locator.relationshipRepository,
                        locator.meRepository,
                    )
                }
            }
    }
}
