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
    val currencyCode: String = "",
    val totalCents: Long = 0L,
    /** Share of the total the counterparty owes, in whole percent. */
    val counterpartySharePercent: Int = 50,
) {
    /**
     * Counterparty share in cents, integer math only: rounds half up
     * on the percentage split so shares always sum to the total.
     */
    val counterpartyShareCents: Long
        get() = (totalCents * counterpartySharePercent + 50) / 100

    val payerShareCents: Long
        get() = totalCents - counterpartyShareCents
}

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
        val cents = parseAmountToCents(amount) ?: 0L
        _state.update { it.copy(amountInput = amount, error = null, totalCents = cents) }
    }

    fun updateCounterpartySharePercent(percent: Int) {
        _state.update { it.copy(counterpartySharePercent = percent.coerceIn(0, 100)) }
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
            _state.update { it.copy(error = "Enter a valid amount") }
            return
        }
        if (current.description.isBlank()) {
            _state.update { it.copy(error = "Description is required") }
            return
        }

        _state.update { it.copy(isSubmitting = true, error = null) }

        val request = CreateExpenseRequest(
            relationship_id = relationshipId,
            payer_user_id = current.myId,
            total_cents = totalCents,
            description = current.description.trim(),
            category = current.category.trim().ifBlank { null },
            shares = listOf(
                ExpenseShareDto(
                    user_id = current.myId,
                    amount_cents = current.payerShareCents,
                ),
                ExpenseShareDto(
                    user_id = current.counterpartyId,
                    amount_cents = current.counterpartyShareCents,
                ),
            ),
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

    companion object {
        fun factory(locator: ServiceLocator, relationshipId: Long): ViewModelProvider.Factory =
            viewModelFactory {
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

/**
 * Parse a decimal money string into integer cents without any float
 * math. Accepts `12`, `12.3`, `12.34`, optional thousands commas.
 * Returns null for anything else (too many decimals, stray characters,
 * negatives).
 */
fun parseAmountToCents(input: String): Long? {
    val clean = input.replace(",", "").trim()
    if (clean.isEmpty()) return null
    if (!Regex("""^\d{1,13}(\.\d{0,2})?$""").matches(clean)) return null
    val parts = clean.split(".")
    val major = parts[0].toLongOrNull() ?: return null
    val minor = if (parts.size == 2 && parts[1].isNotEmpty()) {
        parts[1].padEnd(2, '0').toLongOrNull() ?: return null
    } else {
        0L
    }
    return major * 100 + minor
}
