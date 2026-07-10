package com.rknepp.parity.home.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rknepp.parity.ServiceLocator
import com.rknepp.parity.home.data.MeRepository
import com.rknepp.parity.home.model.UserSummary
import com.rknepp.parity.ledger.data.LedgerRepository
import com.rknepp.parity.network.ApiResult
import com.rknepp.parity.relationships.data.RelationshipRepository
import com.rknepp.parity.relationships.data.RelationshipWithBalance
import com.rknepp.parity.relationships.data.listWithBalances
import com.rknepp.parity.relationships.ui.formatCents
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

/**
 * Net confirmed position across all accepted relationships in one
 * currency. Positive [netCents]: others owe you overall.
 */
data class CurrencyPosition(
    val currencyCode: String,
    val netCents: Long,
)

/** Compact relationship row for the dashboard preview list. */
data class HomeRelationship(
    val id: Long,
    val counterpartyName: String,
    val currencyCode: String,
    /** Positive: they owe you. Null when the balance fetch failed. */
    val netCentsForMe: Long?,
)

/** A pending entry the counterparty (me) must confirm, for the dashboard. */
data class PendingItem(
    val id: Long,
    val kind: Kind,
    val description: String,
    val amountText: String,
    val counterpartyName: String,
    val createdAt: String,
) {
    enum class Kind { EXPENSE, PAYMENT }
}

data class HomeData(
    val user: UserSummary,
    val positions: List<CurrencyPosition>,
    val pending: List<PendingItem>,
    val invitesForMe: Int,
    val invitesSent: Int,
    val activeRelationships: List<HomeRelationship>,
)

sealed interface HomeState {
    data object Loading : HomeState
    data class Loaded(val data: HomeData) : HomeState
    data object Error : HomeState
}

class HomeViewModel(
    private val meRepository: MeRepository,
    private val relationshipRepository: RelationshipRepository,
    private val ledgerRepository: LedgerRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<HomeState>(HomeState.Loading)
    val state: StateFlow<HomeState> = _state.asStateFlow()

    /** True while a user-initiated pull-to-refresh is in flight. */
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private var loadInFlight = false

    init { reload() }

    /** Full load: drops to the Loading state first. */
    fun reload() = load(showLoading = true)

    /** Silent revalidation that keeps current content on screen. */
    fun refresh() = load(showLoading = _state.value !is HomeState.Loaded)

    /** Pull-to-refresh: revalidate while showing the pull indicator. */
    fun pullRefresh() {
        _isRefreshing.value = true
        load(showLoading = false)
    }

    /** Confirm a pending entry, then refresh. Optimistic-free: we reload. */
    fun confirm(item: PendingItem) = act(item) {
        when (item.kind) {
            PendingItem.Kind.EXPENSE -> ledgerRepository.confirmExpense(item.id)
            PendingItem.Kind.PAYMENT -> ledgerRepository.confirmPayment(item.id)
        }
    }

    /** Decline (discard) a pending entry, then refresh. */
    fun decline(item: PendingItem) = act(item) {
        when (item.kind) {
            PendingItem.Kind.EXPENSE -> ledgerRepository.discardExpense(item.id)
            PendingItem.Kind.PAYMENT -> ledgerRepository.discardPayment(item.id)
        }
    }

    private fun act(item: PendingItem, block: suspend () -> ApiResult<*>) {
        viewModelScope.launch {
            if (block() is ApiResult.Success) refresh()
        }
    }

    private fun load(showLoading: Boolean) {
        if (loadInFlight) return
        loadInFlight = true
        if (showLoading) _state.update { HomeState.Loading }
        viewModelScope.launch {
            try {
                val meResult = meRepository.fetchMe()
                if (meResult !is ApiResult.Success) {
                    _state.update { HomeState.Error }
                    return@launch
                }
                val me = meResult.data

                when (val listResult = relationshipRepository.listWithBalances()) {
                    is ApiResult.Success -> {
                        // Pending is supplementary: an error leaves it empty
                        // rather than failing the whole dashboard.
                        val pending = loadPending(me, listResult.data)
                        _state.update {
                            HomeState.Loaded(buildData(me, listResult.data, pending))
                        }
                    }
                    else -> _state.update { HomeState.Error }
                }
            } finally {
                loadInFlight = false
                _isRefreshing.value = false
            }
        }
    }

    private suspend fun loadPending(
        me: UserSummary,
        rows: List<RelationshipWithBalance>,
    ): List<PendingItem> {
        val result = ledgerRepository.listPending()
        if (result !is ApiResult.Success) return emptyList()

        // relationship_id -> (counterparty name, currency) for display.
        val context = rows.associate { row ->
            val rel = row.relationship
            val other = if (rel.invitingUser.id == me.id) rel.invitedUser else rel.invitingUser
            rel.id to (other.displayName to rel.currencyCode)
        }

        val expenses = result.data.expenses.map { e ->
            val (name, currency) = context[e.relationship_id] ?: ("Someone" to "USD")
            PendingItem(
                id = e.id,
                kind = PendingItem.Kind.EXPENSE,
                description = e.description,
                amountText = formatCents(e.total_cents, currency),
                counterpartyName = name,
                createdAt = e.created_at,
            )
        }
        val payments = result.data.payments.map { p ->
            val (name, currency) = context[p.relationship_id] ?: ("Someone" to "USD")
            PendingItem(
                id = p.id,
                kind = PendingItem.Kind.PAYMENT,
                description = p.description,
                amountText = formatCents(p.amount_cents, currency),
                counterpartyName = name,
                createdAt = p.created_at,
            )
        }
        return (expenses + payments).sortedByDescending { it.createdAt }
    }

    private fun buildData(
        me: UserSummary,
        rows: List<RelationshipWithBalance>,
        pending: List<PendingItem>,
    ): HomeData {
        val accepted = rows.filter { it.relationship.status == "accepted" }
        val pendingRels = rows.filter { it.relationship.status == "pending" }

        val active = accepted.map { row ->
            val rel = row.relationship
            val net = row.balance?.confirmed?.let { view ->
                when {
                    view.netCents == 0L -> 0L
                    view.toUserId == me.id -> view.netCents
                    else -> -view.netCents
                }
            }
            val other = if (rel.invitingUser.id == me.id) rel.invitedUser else rel.invitingUser
            HomeRelationship(
                id = rel.id,
                counterpartyName = other.displayName,
                currencyCode = rel.currencyCode,
                netCentsForMe = net,
            )
        }

        val positions = active
            .filter { it.netCentsForMe != null }
            .groupBy { it.currencyCode }
            .map { (code, group) ->
                CurrencyPosition(
                    currencyCode = code,
                    netCents = group.sumOf { it.netCentsForMe ?: 0L },
                )
            }
            .sortedBy { it.currencyCode }

        return HomeData(
            user = me,
            positions = positions,
            pending = pending,
            invitesForMe = pendingRels.count { it.relationship.invitedUser.id == me.id },
            invitesSent = pendingRels.count { it.relationship.invitingUser.id == me.id },
            activeRelationships = active
                .sortedByDescending { it.netCentsForMe?.absoluteValue ?: -1L }
                .take(3),
        )
    }

    companion object {
        fun factory(locator: ServiceLocator): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                HomeViewModel(
                    locator.meRepository,
                    locator.relationshipRepository,
                    locator.ledgerRepository,
                )
            }
        }
    }
}
