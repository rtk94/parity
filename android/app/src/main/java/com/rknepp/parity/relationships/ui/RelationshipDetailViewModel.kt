package com.rknepp.parity.relationships.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rknepp.parity.ServiceLocator
import com.rknepp.parity.home.data.MeRepository
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

data class RelationshipDetailData(
    val counterpartyName: String,
    val counterpartyUsername: String,
    val status: String,
    val currencyCode: String,
    val confirmed: BalanceLine,
    val projected: BalanceLine,
)

sealed interface RelationshipDetailState {
    data object Loading : RelationshipDetailState
    data class Loaded(val data: RelationshipDetailData) : RelationshipDetailState
    data object Error : RelationshipDetailState
}

class RelationshipDetailViewModel(
    private val relationshipId: Long,
    private val relationshipRepository: RelationshipRepository,
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
                    ),
                )
            }
        }
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
                    locator.meRepository,
                )
            }
        }

        @Suppress("unused")
        private fun unusedExtras(extras: CreationExtras) = extras
    }
}
