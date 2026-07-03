package com.rknepp.parity.relationships.data

import com.rknepp.parity.network.ApiResult
import com.rknepp.parity.relationships.data.dto.BalanceResponse
import com.rknepp.parity.relationships.data.dto.RelationshipDto
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * A relationship joined with its balance. [balance] is null for
 * non-accepted relationships (they have no ledger yet) and when the
 * balance fetch failed — the row is still worth showing either way.
 */
data class RelationshipWithBalance(
    val relationship: RelationshipDto,
    val balance: BalanceResponse?,
)

/**
 * Load all relationships plus, in parallel, the balance of each
 * accepted one. List failure fails the whole load; individual balance
 * failures degrade to a null balance.
 */
suspend fun RelationshipRepository.listWithBalances(): ApiResult<List<RelationshipWithBalance>> {
    val items: List<RelationshipDto> = when (val result = list(limit = 100)) {
        is ApiResult.Success -> result.data.items
        is ApiResult.HttpFailure -> return result
        is ApiResult.NetworkFailure -> return result
        is ApiResult.UnexpectedFailure -> return result
    }
    val rows = coroutineScope {
        items.map { rel ->
            async {
                val balance = if (rel.status == "accepted") {
                    (balance(rel.id) as? ApiResult.Success)?.data
                } else {
                    null
                }
                RelationshipWithBalance(rel, balance)
            }
        }.awaitAll()
    }
    return ApiResult.Success(rows)
}
