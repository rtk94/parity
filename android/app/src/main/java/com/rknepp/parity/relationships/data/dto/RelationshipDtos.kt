package com.rknepp.parity.relationships.data.dto

import com.rknepp.parity.home.model.UserSummary
import kotlinx.serialization.Serializable

/**
 * Wire shape of a relationship, matching the backend
 * `serialize_relationship` envelope. Field names mirror the JSON keys;
 * camelCase accessors are exposed for Kotlin call sites.
 */
@Serializable
data class RelationshipDto(
    val id: Long,
    val inviting_user: UserSummary,
    val invited_user: UserSummary,
    val status: String,
    val currency_code: String,
    val created_at: String,
) {
    val invitingUser: UserSummary get() = inviting_user
    val invitedUser: UserSummary get() = invited_user
    val currencyCode: String get() = currency_code
    val createdAt: String get() = created_at
}

/** Paginated list envelope used by all backend list endpoints. */
@Serializable
data class RelationshipListResponse(
    val items: List<RelationshipDto>,
    val total: Int,
    val limit: Int,
    val offset: Int,
)

/** One side of a balance view (`confirmed` or `projected`). */
@Serializable
data class BalanceViewDto(
    val net_cents: Long,
    val from_user_id: Long?,
    val to_user_id: Long?,
) {
    val netCents: Long get() = net_cents
    val fromUserId: Long? get() = from_user_id
    val toUserId: Long? get() = to_user_id
}

/** Response of `GET /relationships/{id}/balance`. */
@Serializable
data class BalanceResponse(
    val relationship_id: Long,
    val confirmed: BalanceViewDto,
    val projected: BalanceViewDto,
) {
    val relationshipId: Long get() = relationship_id
}
