package com.rknepp.parity.home.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rknepp.parity.ServiceLocator
import com.rknepp.parity.home.data.MeRepository
import com.rknepp.parity.home.model.UserSummary
import com.rknepp.parity.network.ApiResult
import com.rknepp.parity.relationships.data.RelationshipRepository
import com.rknepp.parity.relationships.data.RelationshipWithBalance
import com.rknepp.parity.relationships.data.listWithBalances
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

data class HomeData(
    val user: UserSummary,
    val positions: List<CurrencyPosition>,
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
) : ViewModel() {

    private val _state = MutableStateFlow<HomeState>(HomeState.Loading)
    val state: StateFlow<HomeState> = _state.asStateFlow()

    private var loadInFlight = false

    init { reload() }

    /** Full load: drops to the Loading state first. */
    fun reload() = load(showLoading = true)

    /** Silent revalidation that keeps current content on screen. */
    fun refresh() = load(showLoading = _state.value !is HomeState.Loaded)

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
                        _state.update { HomeState.Loaded(buildData(me, listResult.data)) }
                    }
                    else -> _state.update { HomeState.Error }
                }
            } finally {
                loadInFlight = false
            }
        }
    }

    private fun buildData(me: UserSummary, rows: List<RelationshipWithBalance>): HomeData {
        val accepted = rows.filter { it.relationship.status == "accepted" }
        val pending = rows.filter { it.relationship.status == "pending" }

        val active = accepted.map { row ->
            val rel = row.relationship
            val other = if (rel.invitingUser.id == me.id) rel.invitedUser else rel.invitingUser
            val net = row.balance?.confirmed?.let { view ->
                when {
                    view.netCents == 0L -> 0L
                    view.toUserId == me.id -> view.netCents
                    else -> -view.netCents
                }
            }
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
            invitesForMe = pending.count { it.relationship.invitedUser.id == me.id },
            invitesSent = pending.count { it.relationship.invitingUser.id == me.id },
            activeRelationships = active
                .sortedByDescending { it.netCentsForMe?.absoluteValue ?: -1L }
                .take(3),
        )
    }

    companion object {
        fun factory(locator: ServiceLocator): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                HomeViewModel(locator.meRepository, locator.relationshipRepository)
            }
        }
    }
}
