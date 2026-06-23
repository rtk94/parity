package com.rknepp.parity.relationships.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rknepp.parity.ServiceLocator
import com.rknepp.parity.home.data.MeRepository
import com.rknepp.parity.ledger.data.LedgerRepository
import com.rknepp.parity.network.ApiResult
import com.rknepp.parity.relationships.data.RelationshipRepository
import com.rknepp.parity.relationships.data.dto.BalanceViewDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A balance rendered from the caller's point of view. [settled] means
 * net is zero. Otherwise exactly one of the owe directions is set.
 */
data class BalanceLine(
    val settled: Boolean,
    val youOwe: Boolean,
    val amountText: String,
    val counterpartyName: String,
)

data class LedgerRow(
    val id: Long,
    val type: String, // "expense" or "payment"
    val description: String,
    val category: String?,
    val amountText: String,
    val status: String,
    val timestamp: String,
    val isPayer: Boolean, // For expense: did I pay? For payment: did I send?
    val canConfirm: Boolean,
    val canDiscard: Boolean,
    val canReverse: Boolean,
    val comments: List<com.rknepp.parity.ledger.data.dto.CommentDto>? = null,
    val isLoadingComments: Boolean = false,
)

data class RelationshipDetailData(
    val counterpartyName: String,
    val counterpartyUsername: String,
    val status: String,
    val currencyCode: String,
    val confirmed: BalanceLine,
    val projected: BalanceLine,
    val canAccept: Boolean,
    val canReject: Boolean,
    val ledgerItems: List<LedgerRow>,
)

sealed interface RelationshipDetailState {
    data object Loading : RelationshipDetailState
    data class Loaded(val data: RelationshipDetailData) : RelationshipDetailState
    data object Error : RelationshipDetailState
}

class RelationshipDetailViewModel(
    private val relationshipId: Long,
    private val relationshipRepository: RelationshipRepository,
    private val ledgerRepository: LedgerRepository,
    private val meRepository: MeRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<RelationshipDetailState>(RelationshipDetailState.Loading)
    val state: StateFlow<RelationshipDetailState> = _state.asStateFlow()

    init { reload() }

    fun reload() {
        _state.update { RelationshipDetailState.Loading }
        viewModelScope.launch {
            val meResult = meRepository.fetchMe()
            if (meResult !is ApiResult.Success) {
                _state.update { RelationshipDetailState.Error }
                return@launch
            }
            val myId = meResult.data.id

            val relResult = relationshipRepository.get(relationshipId)
            if (relResult !is ApiResult.Success) {
                _state.update { RelationshipDetailState.Error }
                return@launch
            }
            val rel = relResult.data

            val balResult = relationshipRepository.balance(relationshipId)
            if (balResult !is ApiResult.Success) {
                _state.update { RelationshipDetailState.Error }
                return@launch
            }
            val bal = balResult.data

            val expensesResult = ledgerRepository.listExpenses(relationshipId)
            val paymentsResult = ledgerRepository.listPayments(relationshipId)
            
            val expenses = if (expensesResult is ApiResult.Success) expensesResult.data.items else emptyList()
            val payments = if (paymentsResult is ApiResult.Success) paymentsResult.data.items else emptyList()

            val ledgerRows = mutableListOf<LedgerRow>()
            for (e in expenses) {
                ledgerRows.add(
                    LedgerRow(
                        id = e.id,
                        type = "expense",
                        description = e.description,
                        category = e.category,
                        amountText = formatCents(e.total_cents, rel.currencyCode),
                        status = e.status,
                        timestamp = e.created_at,
                        isPayer = e.payer_user_id == myId,
                        canConfirm = e.status == "pending" && e.created_by_user_id != myId,
                        canDiscard = e.status == "pending",
                        canReverse = e.status == "confirmed" && e.reverses_expense_id == null,
                    )
                )
            }
            for (p in payments) {
                ledgerRows.add(
                    LedgerRow(
                        id = p.id,
                        type = "payment",
                        description = p.description,
                        category = null,
                        amountText = formatCents(p.amount_cents, rel.currencyCode),
                        status = p.status,
                        timestamp = p.created_at,
                        isPayer = p.from_user_id == myId,
                        canConfirm = p.status == "pending" && p.created_by_user_id != myId,
                        canDiscard = p.status == "pending",
                        canReverse = p.status == "confirmed" && p.reverses_payment_id == null,
                    )
                )
            }
            ledgerRows.sortByDescending { it.timestamp }

            val other = if (rel.invitingUser.id == myId) rel.invitedUser else rel.invitingUser

            _state.update {
                RelationshipDetailState.Loaded(
                    RelationshipDetailData(
                        counterpartyName = other.displayName,
                        counterpartyUsername = other.username,
                        status = rel.status,
                        currencyCode = rel.currencyCode,
                        confirmed = bal.confirmed.toLine(myId, rel.currencyCode, other.displayName),
                        projected = bal.projected.toLine(myId, rel.currencyCode, other.displayName),
                        canAccept = rel.status == "pending" && rel.invitedUser.id == myId,
                        canReject = rel.status == "pending",
                        ledgerItems = ledgerRows,
                    ),
                )
            }
        }
    }

    fun accept() {
        viewModelScope.launch {
            if (relationshipRepository.accept(relationshipId) is ApiResult.Success) {
                reload()
            }
        }
    }

    fun reject() {
        viewModelScope.launch {
            if (relationshipRepository.reject(relationshipId) is ApiResult.Success) {
                reload()
            }
        }
    }

    fun confirmExpense(id: Long) {
        viewModelScope.launch {
            if (ledgerRepository.confirmExpense(id) is ApiResult.Success) reload()
        }
    }

    fun discardExpense(id: Long) {
        viewModelScope.launch {
            if (ledgerRepository.discardExpense(id) is ApiResult.Success) reload()
        }
    }

    fun reverseExpense(id: Long) {
        viewModelScope.launch {
            if (ledgerRepository.reverseExpense(id) is ApiResult.Success) reload()
        }
    }

    fun confirmPayment(id: Long) {
        viewModelScope.launch {
            if (ledgerRepository.confirmPayment(id) is ApiResult.Success) reload()
        }
    }

    fun discardPayment(id: Long) {
        viewModelScope.launch {
            if (ledgerRepository.discardPayment(id) is ApiResult.Success) reload()
        }
    }

    fun reversePayment(id: Long) {
        viewModelScope.launch {
            if (ledgerRepository.reversePayment(id) is ApiResult.Success) reload()
        }
    }

    fun toggleComments(id: Long, type: String) {
        val currentState = _state.value as? RelationshipDetailState.Loaded ?: return
        val items = currentState.data.ledgerItems
        val item = items.find { it.id == id && it.type == type } ?: return
        
        if (item.comments != null) {
            // Already loaded, let's just clear them to hide
            updateItem(id, type) { it.copy(comments = null) }
        } else {
            // Load them
            updateItem(id, type) { it.copy(isLoadingComments = true) }
            viewModelScope.launch {
                val res = if (type == "expense") {
                    ledgerRepository.listExpenseComments(id)
                } else {
                    ledgerRepository.listPaymentComments(id)
                }
                if (res is ApiResult.Success) {
                    updateItem(id, type) { it.copy(isLoadingComments = false, comments = res.data.items) }
                } else {
                    updateItem(id, type) { it.copy(isLoadingComments = false) }
                }
            }
        }
    }

    fun postComment(id: Long, type: String, content: String) {
        if (content.isBlank()) return
        viewModelScope.launch {
            val res = if (type == "expense") {
                ledgerRepository.createExpenseComment(id, content)
            } else {
                ledgerRepository.createPaymentComment(id, content)
            }
            if (res is ApiResult.Success) {
                // Reload comments
                val resList = if (type == "expense") {
                    ledgerRepository.listExpenseComments(id)
                } else {
                    ledgerRepository.listPaymentComments(id)
                }
                if (resList is ApiResult.Success) {
                    updateItem(id, type) { it.copy(comments = resList.data.items) }
                }
            }
        }
    }

    private fun updateItem(id: Long, type: String, updater: (LedgerRow) -> LedgerRow) {
        val currentState = _state.value as? RelationshipDetailState.Loaded ?: return
        val newItems = currentState.data.ledgerItems.map { 
            if (it.id == id && it.type == type) updater(it) else it 
        }
        _state.update { currentState.copy(data = currentState.data.copy(ledgerItems = newItems)) }
    }

    private fun BalanceViewDto.toLine(
        myId: Long,
        currencyCode: String,
        counterpartyName: String,
    ): BalanceLine {
        if (netCents == 0L || fromUserId == null || toUserId == null) {
            return BalanceLine(
                settled = true,
                youOwe = false,
                amountText = formatCents(0, currencyCode),
                counterpartyName = counterpartyName,
            )
        }
        // Backend convention: `from_user_id` owes `to_user_id`.
        val youOwe = fromUserId == myId
        return BalanceLine(
            settled = false,
            youOwe = youOwe,
            amountText = formatCents(netCents, currencyCode),
            counterpartyName = counterpartyName,
        )
    }

    companion object {
        fun factory(
            locator: ServiceLocator,
            relationshipId: Long,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                RelationshipDetailViewModel(
                    relationshipId,
                    locator.relationshipRepository,
                    locator.ledgerRepository,
                    locator.meRepository,
                )
            }
        }

        @Suppress("unused")
        private fun unusedExtras(extras: CreationExtras) = extras
    }
}
