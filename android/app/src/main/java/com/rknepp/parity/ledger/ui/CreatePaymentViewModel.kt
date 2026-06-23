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
    val isUserPaying: Boolean = true,
)

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
                        counterpartyName = other.displayName
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

        val totalCents = parseAmountToCents(current.amountInput)
        if (totalCents == null || totalCents <= 0) {
            _state.update { it.copy(error = "Invalid amount") }
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
            amount_cents = totalCents,
            description = current.description,
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

    private fun parseAmountToCents(input: String): Long? {
        val clean = input.replace(",", "").trim()
        val parts = clean.split(".")
        if (parts.size > 2) return null
        val dollars = parts[0].toLongOrNull() ?: 0L
        val cents = if (parts.size == 2) {
            val c = parts[1].padEnd(2, '0').take(2)
            c.toLongOrNull() ?: 0L
        } else {
            0L
        }
        return (dollars * 100) + cents
    }

    companion object {
        fun factory(locator: ServiceLocator, relationshipId: Long): ViewModelProvider.Factory = viewModelFactory {
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
