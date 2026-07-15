package com.rknepp.parity.ledger.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class ExpenseShareDto(
    val user_id: Long,
    val amount_cents: Long,
)

@Serializable
data class ExpenseDto(
    val id: Long,
    val relationship_id: Long,
    val payer_user_id: Long,
    val total_cents: Long,
    val description: String,
    val category: String? = null,
    val created_by_user_id: Long,
    val created_at: String,
    val status: String,
    val confirmed_at: String? = null,
    val confirmed_by_user_id: Long? = null,
    val discarded_at: String? = null,
    val discarded_by_user_id: Long? = null,
    val rejection_reason: String? = null,
    val reverses_expense_id: Long? = null,
    val shares: List<ExpenseShareDto>,
)

@Serializable
data class PaymentDto(
    val id: Long,
    val relationship_id: Long,
    val from_user_id: Long,
    val to_user_id: Long,
    val amount_cents: Long,
    val description: String,
    val created_by_user_id: Long,
    val created_at: String,
    val status: String,
    val confirmed_at: String? = null,
    val confirmed_by_user_id: Long? = null,
    val discarded_at: String? = null,
    val discarded_by_user_id: Long? = null,
    val rejection_reason: String? = null,
    val reverses_payment_id: Long? = null,
)

@Serializable
data class CreateExpenseRequest(
    val relationship_id: Long,
    val payer_user_id: Long,
    val total_cents: Long,
    val description: String,
    val category: String? = null,
    val shares: List<ExpenseShareDto>,
)

@Serializable
data class CreatePaymentRequest(
    val relationship_id: Long,
    val from_user_id: Long,
    val to_user_id: Long,
    val amount_cents: Long,
    val description: String,
)

@Serializable
data class DiscardRequest(
    val reason: String? = null,
)

@Serializable
data class ExpenseListResponse(
    val items: List<ExpenseDto>,
    val total: Int,
    val limit: Int,
    val offset: Int,
)

@Serializable
data class PaymentListResponse(
    val items: List<PaymentDto>,
    val total: Int,
    val limit: Int,
    val offset: Int,
)

/** GET /pending: entries across all relationships awaiting my confirmation. */
@Serializable
data class PendingResponse(
    val expenses: List<ExpenseDto>,
    val payments: List<PaymentDto>,
)

@Serializable
data class CommentDto(
    val id: Long,
    val user_id: Long,
    val expense_id: Long? = null,
    val payment_id: Long? = null,
    val content: String,
    val created_at: String,
)

@Serializable
data class CreateCommentRequest(
    val content: String,
)

@Serializable
data class CommentListResponse(
    val items: List<CommentDto>,
)

@Serializable
data class AttachmentDto(
    val id: Long,
    val expense_id: Long,
    val uploaded_by_user_id: Long,
    val filename: String,
    val content_type: String,
    val size_bytes: Long,
    val checksum_sha256: String,
    val created_at: String,
)

@Serializable
data class AttachmentListResponse(
    val items: List<AttachmentDto>,
)
