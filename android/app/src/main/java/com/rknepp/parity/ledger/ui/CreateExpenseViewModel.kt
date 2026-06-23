package com.rknepp.parity.ledger.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rknepp.parity.ServiceLocator
import com.rknepp.parity.home.data.MeRepository
import com.rknepp.parity.ledger.data.LedgerRepository
import com.rknepp.parity.ledger.data.dto.CreateExpenseRequest
import com.rknepp.parity.ledger.data.dto.ExpenseShareDto
import com.rknepp.parity.network.ApiResult
import com.rknepp.parity.relationships.data.RelationshipRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CreateExpenseState(
    val amountInput: String = "",
    val description: String = "",
    val category: String = "",
    val isSubmitting: Boolean = false,
    val isReady: Boolean = false,
    val error: String? = null,
    val success: Boolean = false,
    val myId: Long = 0,
    val counterpartyId: Long = 0,
    val counterpartyName: String = "",
    val totalCents: Long = 0L,
    val counterpartySharePercentage: Float = 50f,
)

class CreateExpenseViewModel(
    private val relationshipId: Long,
    private val ledgerRepository: LedgerRepository,
    private val relationshipRepository: RelationshipRepository,
    private val meRepository: MeRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(CreateExpenseState())
    val state: StateFlow<CreateExpenseState> = _state.asStateFlow()

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
        val cents = parseAmountToCents(amount) ?: 0L
        _state.update { it.copy(amountInput = amount, error = null, totalCents = cents) }
    }

    fun updateCounterpartySharePercentage(percentage: Float) {
        _state.update { it.copy(counterpartySharePercentage = percentage) }
    }

    fun updateDescription(description: String) {
        _state.update { it.copy(description = description, error = null) }
    }

    fun updateCategory(category: String) {
        _state.update { it.copy(category = category, error = null) }
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

        val otherShare = (totalCents * current.counterpartySharePercentage / 100f).toLong()
        val payerShare = totalCents - otherShare

        val request = CreateExpenseRequest(
            relationship_id = relationshipId,
            payer_user_id = current.myId,
            total_cents = totalCents,
            description = current.description.trim(),
            category = current.category.trim().ifBlank { null },
            shares = listOf(
                ExpenseShareDto(user_id = current.myId, amount_cents = payerShare),
                ExpenseShareDto(user_id = current.counterpartyId, amount_cents = otherShare)
            )
        )

        viewModelScope.launch {
            when (val result = ledgerRepository.createExpense(request)) {
                is ApiResult.Success -> {
                    _state.update { it.copy(isSubmitting = false, success = true) }
                }
                is ApiResult.HttpFailure -> {
                    val msg = result.error?.message ?: "Failed to create expense"
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
                CreateExpenseViewModel(
                    relationshipId,
                    locator.ledgerRepository,
                    locator.relationshipRepository,
                    locator.meRepository,
                )
            }
        }
    }
}
