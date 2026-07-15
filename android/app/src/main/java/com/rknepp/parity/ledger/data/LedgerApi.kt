package com.rknepp.parity.ledger.data

import com.rknepp.parity.ledger.data.dto.CreateExpenseRequest
import com.rknepp.parity.ledger.data.dto.CreatePaymentRequest
import com.rknepp.parity.ledger.data.dto.DiscardRequest
import com.rknepp.parity.ledger.data.dto.ExpenseDto
import com.rknepp.parity.ledger.data.dto.ExpenseListResponse
import com.rknepp.parity.ledger.data.dto.PaymentDto
import com.rknepp.parity.ledger.data.dto.PaymentListResponse
import com.rknepp.parity.ledger.data.dto.PendingResponse
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

interface LedgerApi {
    @GET("api/v1/pending")
    suspend fun listPending(): Response<PendingResponse>

    @GET("api/v1/expenses")
    suspend fun listExpenses(
        @Query("relationship_id") relationshipId: Long,
        @Query("limit") limit: Int = 100,
        @Query("offset") offset: Int = 0,
    ): Response<ExpenseListResponse>

    @POST("api/v1/expenses")
    suspend fun createExpense(@Body request: CreateExpenseRequest): Response<ExpenseDto>

    @POST("api/v1/expenses/{id}/confirm")
    suspend fun confirmExpense(@Path("id") id: Long): Response<ExpenseDto>

    @POST("api/v1/expenses/{id}/discard")
    suspend fun discardExpense(@Path("id") id: Long, @Body request: DiscardRequest = DiscardRequest()): Response<ExpenseDto>

    @POST("api/v1/expenses/{id}/reverse")
    suspend fun reverseExpense(@Path("id") id: Long): Response<ExpenseDto>

    @GET("api/v1/payments")
    suspend fun listPayments(
        @Query("relationship_id") relationshipId: Long,
        @Query("limit") limit: Int = 100,
        @Query("offset") offset: Int = 0,
    ): Response<PaymentListResponse>

    @POST("api/v1/payments")
    suspend fun createPayment(@Body request: CreatePaymentRequest): Response<PaymentDto>

    @POST("api/v1/payments/{id}/confirm")
    suspend fun confirmPayment(@Path("id") id: Long): Response<PaymentDto>

    @POST("api/v1/payments/{id}/discard")
    suspend fun discardPayment(@Path("id") id: Long, @Body request: DiscardRequest = DiscardRequest()): Response<PaymentDto>

    @POST("api/v1/payments/{id}/reverse")
    suspend fun reversePayment(@Path("id") id: Long): Response<PaymentDto>

    @GET("api/v1/expenses/{id}/comments")
    suspend fun listExpenseComments(@Path("id") id: Long): Response<com.rknepp.parity.ledger.data.dto.CommentListResponse>

    @POST("api/v1/expenses/{id}/comments")
    suspend fun createExpenseComment(@Path("id") id: Long, @Body request: com.rknepp.parity.ledger.data.dto.CreateCommentRequest): Response<com.rknepp.parity.ledger.data.dto.CommentDto>

    @GET("api/v1/payments/{id}/comments")
    suspend fun listPaymentComments(@Path("id") id: Long): Response<com.rknepp.parity.ledger.data.dto.CommentListResponse>

    @POST("api/v1/payments/{id}/comments")
    suspend fun createPaymentComment(@Path("id") id: Long, @Body request: com.rknepp.parity.ledger.data.dto.CreateCommentRequest): Response<com.rknepp.parity.ledger.data.dto.CommentDto>

    // Attachments are expense-only (receipts). The upload field name must
    // be "file" to match the backend's multipart handler.
    @GET("api/v1/expenses/{id}/attachments")
    suspend fun listAttachments(@Path("id") id: Long): Response<com.rknepp.parity.ledger.data.dto.AttachmentListResponse>

    @Multipart
    @POST("api/v1/expenses/{id}/attachments")
    suspend fun uploadAttachment(@Path("id") id: Long, @Part file: MultipartBody.Part): Response<com.rknepp.parity.ledger.data.dto.AttachmentDto>

    @GET("api/v1/attachments/{id}")
    suspend fun downloadAttachment(@Path("id") id: Long): Response<ResponseBody>

    @DELETE("api/v1/attachments/{id}")
    suspend fun deleteAttachment(@Path("id") id: Long): Response<Unit>
}
