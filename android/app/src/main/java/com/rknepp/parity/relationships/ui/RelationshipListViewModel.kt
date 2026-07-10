package com.rknepp.parity.relationships.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rknepp.parity.ServiceLocator
import com.rknepp.parity.home.data.MeRepository
import com.rknepp.parity.network.ApiResult
import com.rknepp.parity.relationships.data.RelationshipRepository
import com.rknepp.parity.relationships.data.RelationshipWithBalance
import com.rknepp.parity.relationships.data.listWithBalances
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One row in the relationships list, pre-resolved to the counterparty. */
data class RelationshipRow(
    val id: Long,
    val counterpartyName: String,
    val counterpartyUsername: String,
    val status: String,
    val currencyCode: String,
    val canAccept: Boolean,
    val canReject: Boolean,
    /**
     * Net confirmed cents from the caller's perspective: positive
     * means the counterparty owes you. Null when the relationship has
     * no ledger yet or the balance fetch failed.
     */
    val netCentsForMe: Long? = null,
    /** True when the caller sent this (still pending) invite. */
    val isInviter: Boolean = false,
)

sealed interface RelationshipListState {
    data object Loading : RelationshipListState
    data class Loaded(val rows: List<RelationshipRow>) : RelationshipListState
    data object Empty : RelationshipListState
    data object Error : RelationshipListState
}

class RelationshipListViewModel(
    private val relationshipRepository: RelationshipRepository,
    private val meRepository: MeRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<RelationshipListState>(RelationshipListState.Loading)
    val state: StateFlow<RelationshipListState> = _state.asStateFlow()

    /** One-shot user-facing error for row actions, shown in a snackbar. */
    private val _actionError = MutableStateFlow<String?>(null)
    val actionError: StateFlow<String?> = _actionError.asStateFlow()

    /** True while a user-initiated pull-to-refresh is in flight. */
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private var loadInFlight = false

    init { reload() }

    /** Full load: drops to the Loading state first. */
    fun reload() = load(showLoading = true)

    /** Pull-to-refresh: revalidate while showing the pull indicator. */
    fun pullRefresh() {
        _isRefreshing.value = true
        load(showLoading = false)
    }

    /**
     * Silent revalidation for on-resume refreshes: keeps current rows
     * on screen while fetching, so returning from another screen
     * doesn't flash a spinner.
     */
    fun refresh() = load(showLoading = _state.value !is RelationshipListState.Loaded)

    private fun load(showLoading: Boolean) {
        if (loadInFlight) return
        loadInFlight = true
        if (showLoading) _state.update { RelationshipListState.Loading }
        viewModelScope.launch {
            try {
                val meResult = meRepository.fetchMe()
                if (meResult !is ApiResult.Success) {
                    _state.update { RelationshipListState.Error }
                    return@launch
                }
                val myId = meResult.data.id

                when (val listResult = relationshipRepository.listWithBalances()) {
                    is ApiResult.Success -> {
                        val rows = listResult.data
                            .filter { it.relationship.status != "rejected" }
                            .map { it.toRow(myId) }
                            .sortedBy { it.sortRank() }
                        _state.update {
                            if (rows.isEmpty()) RelationshipListState.Empty
                            else RelationshipListState.Loaded(rows)
                        }
                    }
                    else -> _state.update { RelationshipListState.Error }
                }
            } finally {
                loadInFlight = false
                _isRefreshing.value = false
            }
        }
    }

    fun accept(id: Long) {
        viewModelScope.launch {
            when (relationshipRepository.accept(id)) {
                is ApiResult.Success -> refresh()
                else -> _actionError.update { "Could not accept the invitation. Try again." }
            }
        }
    }

    fun reject(id: Long) {
        viewModelScope.launch {
            when (relationshipRepository.reject(id)) {
                is ApiResult.Success -> refresh()
                else -> _actionError.update { "Could not decline the invitation. Try again." }
            }
        }
    }

    fun consumeActionError() {
        _actionError.update { null }
    }

    private fun RelationshipWithBalance.toRow(myId: Long): RelationshipRow {
        val rel = relationship
        val other = if (rel.invitingUser.id == myId) rel.invitedUser else rel.invitingUser
        val net = balance?.confirmed?.let { view ->
            when {
                view.netCents == 0L -> 0L
                view.toUserId == myId -> view.netCents
                else -> -view.netCents
            }
        }
        return RelationshipRow(
            id = rel.id,
            counterpartyName = other.displayName,
            counterpartyUsername = other.username,
            status = rel.status,
            currencyCode = rel.currencyCode,
            canAccept = rel.status == "pending" && rel.invitedUser.id == myId,
            canReject = rel.status == "pending",
            netCentsForMe = net,
            isInviter = rel.invitingUser.id == myId,
        )
    }

    private fun RelationshipRow.sortRank(): Int = when {
        canAccept -> 0 // invitations for me first
        status == "accepted" -> 1
        else -> 2 // invites I sent
    }

    companion object {
        fun factory(locator: ServiceLocator): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                RelationshipListViewModel(
                    locator.relationshipRepository,
                    locator.meRepository,
                )
            }
        }
    }
}
