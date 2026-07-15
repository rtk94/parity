package com.rknepp.parity.ledger.data

import com.rknepp.parity.ledger.data.dto.CreateExpenseRequest
import com.rknepp.parity.ledger.data.dto.CreatePaymentRequest
import com.rknepp.parity.ledger.data.dto.DiscardRequest
import com.rknepp.parity.ledger.data.dto.ExpenseDto
import com.rknepp.parity.ledger.data.dto.ExpenseListResponse
import com.rknepp.parity.ledger.data.dto.PaymentDto
import com.rknepp.parity.ledger.data.dto.PaymentListResponse
import com.rknepp.parity.ledger.data.dto.PendingResponse
import com.rknepp.parity.ledger.data.dto.AttachmentDto
import com.rknepp.parity.ledger.data.dto.AttachmentListResponse
import com.rknepp.parity.network.ApiResult
import com.rknepp.parity.network.apiCall
import com.rknepp.parity.network.apiCallUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class LedgerRepository(
    private val apiProvider: () -> LedgerApi,
) {
    suspend fun listPending(): ApiResult<PendingResponse> = apiCall {
        apiProvider().listPending()
    }

    suspend fun listExpenses(relationshipId: Long): ApiResult<ExpenseListResponse> = apiCall {
        apiProvider().listExpenses(relationshipId)
    }

    suspend fun createExpense(request: CreateExpenseRequest): ApiResult<ExpenseDto> = apiCall {
        apiProvider().createExpense(request)
    }

    suspend fun confirmExpense(id: Long): ApiResult<ExpenseDto> = apiCall {
        apiProvider().confirmExpense(id)
    }

    suspend fun discardExpense(id: Long, reason: String? = null): ApiResult<ExpenseDto> = apiCall {
        apiProvider().discardExpense(id, DiscardRequest(reason))
    }

    suspend fun reverseExpense(id: Long): ApiResult<ExpenseDto> = apiCall {
        apiProvider().reverseExpense(id)
    }

    suspend fun listPayments(relationshipId: Long): ApiResult<PaymentListResponse> = apiCall {
        apiProvider().listPayments(relationshipId)
    }

    suspend fun createPayment(request: CreatePaymentRequest): ApiResult<PaymentDto> = apiCall {
        apiProvider().createPayment(request)
    }

    suspend fun confirmPayment(id: Long): ApiResult<PaymentDto> = apiCall {
        apiProvider().confirmPayment(id)
    }

    suspend fun discardPayment(id: Long, reason: String? = null): ApiResult<PaymentDto> = apiCall {
        apiProvider().discardPayment(id, DiscardRequest(reason))
    }

    suspend fun reversePayment(id: Long): ApiResult<PaymentDto> = apiCall {
        apiProvider().reversePayment(id)
    }

    suspend fun listExpenseComments(id: Long): ApiResult<com.rknepp.parity.ledger.data.dto.CommentListResponse> = apiCall {
        apiProvider().listExpenseComments(id)
    }

    suspend fun createExpenseComment(id: Long, content: String): ApiResult<com.rknepp.parity.ledger.data.dto.CommentDto> = apiCall {
        apiProvider().createExpenseComment(id, com.rknepp.parity.ledger.data.dto.CreateCommentRequest(content))
    }

    suspend fun listPaymentComments(id: Long): ApiResult<com.rknepp.parity.ledger.data.dto.CommentListResponse> = apiCall {
        apiProvider().listPaymentComments(id)
    }

    suspend fun createPaymentComment(id: Long, content: String): ApiResult<com.rknepp.parity.ledger.data.dto.CommentDto> = apiCall {
        apiProvider().createPaymentComment(id, com.rknepp.parity.ledger.data.dto.CreateCommentRequest(content))
    }

    // --- Attachments (expense receipts) ---------------------------------

    suspend fun listAttachments(expenseId: Long): ApiResult<AttachmentListResponse> = apiCall {
        apiProvider().listAttachments(expenseId)
    }

    suspend fun uploadAttachment(
        expenseId: Long,
        filename: String,
        contentType: String,
        bytes: ByteArray,
    ): ApiResult<AttachmentDto> = apiCall {
        val body = bytes.toRequestBody(contentType.toMediaTypeOrNull(), 0, bytes.size)
        val part = MultipartBody.Part.createFormData("file", filename, body)
        apiProvider().uploadAttachment(expenseId, part)
    }

    /**
     * Downloads an attachment's raw bytes. The response body is read fully
     * on the IO dispatcher (attachments are capped at 10 MB server-side).
     */
    suspend fun downloadAttachment(id: Long): ApiResult<ByteArray> =
        when (val result = apiCall { apiProvider().downloadAttachment(id) }) {
            is ApiResult.Success -> withContext(Dispatchers.IO) {
                try {
                    ApiResult.Success(result.data.bytes())
                } catch (io: IOException) {
                    ApiResult.NetworkFailure(io)
                } finally {
                    result.data.close()
                }
            }
            is ApiResult.HttpFailure -> result
            is ApiResult.NetworkFailure -> result
            is ApiResult.UnexpectedFailure -> result
        }

    suspend fun deleteAttachment(id: Long): ApiResult<Unit> = apiCallUnit {
        apiProvider().deleteAttachment(id)
    }
}
