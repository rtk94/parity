package com.rknepp.parity.admin.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class AdminStatsDto(
    val users: Long,
    val relationships: Long,
    val expenses: Long,
    val payments: Long,
    val comments: Long,
    val active_tokens: Long,
    val audit_entries: Long,
)

@Serializable
data class ResetLedgerRequest(
    val confirm: String,
)

@Serializable
data class DeletedCounts(
    val expenses: Long,
    val expense_shares: Long,
    val payments: Long,
    val comments: Long,
)

@Serializable
data class ResetLedgerResponse(
    val deleted: DeletedCounts,
)

@Serializable
data class CleanupTokensResponse(
    val deleted_tokens: Long,
)
