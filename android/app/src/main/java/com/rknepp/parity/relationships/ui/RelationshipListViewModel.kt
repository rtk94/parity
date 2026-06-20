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
import com.rknepp.parity.relationships.data.dto.RelationshipDto
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

    init { reload() }

    fun reload() {
        _state.update { RelationshipListState.Loading }
        viewModelScope.launch {
            val meResult = meRepository.fetchMe()
            if (meResult !is ApiResult.Success) {
                _state.update { RelationshipListState.Error }
                return@launch
            }
            val myId = meResult.data.id

            when (val listResult = relationshipRepository.list()) {
                is ApiResult.Success -> {
                    val rows = listResult.data.items.map { it.toRow(myId) }
                    _state.update {
                        if (rows.isEmpty()) RelationshipListState.Empty
                        else RelationshipListState.Loaded(rows)
                    }
                }
                else -> _state.update { RelationshipListState.Error }
            }
        }
    }

    private fun RelationshipDto.toRow(myId: Long): RelationshipRow {
        val other = if (invitingUser.id == myId) invitedUser else invitingUser
        return RelationshipRow(
            id = id,
            counterpartyName = other.displayName,
            counterpartyUsername = other.username,
            status = status,
            currencyCode = currencyCode,
        )
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
