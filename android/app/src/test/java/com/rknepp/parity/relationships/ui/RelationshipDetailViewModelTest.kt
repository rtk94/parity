package com.rknepp.parity.relationships.ui

import app.cash.turbine.test
import com.rknepp.parity.MainDispatcherRule
import com.rknepp.parity.auth.data.AuthApi
import com.rknepp.parity.auth.data.dto.LoginRequest
import com.rknepp.parity.auth.data.dto.LoginResponse
import com.rknepp.parity.auth.data.dto.RegisterRequest
import com.rknepp.parity.home.data.MeRepository
import com.rknepp.parity.home.model.UserSummary
import com.rknepp.parity.relationships.data.RelationshipApi
import com.rknepp.parity.relationships.data.RelationshipRepository
import com.rknepp.parity.relationships.data.dto.BalanceResponse
import com.rknepp.parity.relationships.data.dto.BalanceViewDto
import com.rknepp.parity.relationships.data.dto.RelationshipDto
import com.rknepp.parity.relationships.data.dto.RelationshipListResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import retrofit2.Response

class RelationshipDetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val fakeAuthApi = object : AuthApi {
        var meResponse: Response<UserSummary>? = null
        override suspend fun me() = meResponse ?: error("not set")
        override suspend fun register(body: RegisterRequest) = error("unused")
        override suspend fun login(body: LoginRequest) = error("unused")
        override suspend fun logout() = error("unused")
        override suspend fun refresh() = error("unused")
        override suspend fun changePassword(body: com.rknepp.parity.auth.data.dto.ChangePasswordRequest) = error("unused")
        override suspend fun updateProfile(body: com.rknepp.parity.auth.data.dto.UpdateProfileRequest) = error("unused")
    }

    private val fakeRelationshipApi = object : RelationshipApi {
        var getResponse: Response<RelationshipDto>? = null
        var balanceResponse: Response<BalanceResponse>? = null
        override suspend fun list(status: String?, limit: Int?, offset: Int?) = error("unused")
        override suspend fun get(id: Long) = getResponse ?: error("not set")
        override suspend fun balance(id: Long) = balanceResponse ?: error("not set")
        override suspend fun create(request: com.rknepp.parity.relationships.data.dto.CreateRelationshipRequest) = error("unused")
        override suspend fun accept(id: Long) = error("unused")
        override suspend fun reject(id: Long) = error("unused")
    }

    private val fakeLedgerApi = object : com.rknepp.parity.ledger.data.LedgerApi {
        var listExpensesResponse: Response<com.rknepp.parity.ledger.data.dto.ExpenseListResponse>? = null
        var listPaymentsResponse: Response<com.rknepp.parity.ledger.data.dto.PaymentListResponse>? = null
        override suspend fun listExpenses(relationshipId: Long, limit: Int, offset: Int) = listExpensesResponse ?: Response.success(com.rknepp.parity.ledger.data.dto.ExpenseListResponse(emptyList(), 0, 100, 0))
        override suspend fun createExpense(request: com.rknepp.parity.ledger.data.dto.CreateExpenseRequest) = error("unused")
        override suspend fun confirmExpense(id: Long) = error("unused")
        override suspend fun discardExpense(id: Long, request: com.rknepp.parity.ledger.data.dto.DiscardRequest) = error("unused")
        override suspend fun reverseExpense(id: Long): Response<com.rknepp.parity.ledger.data.dto.ExpenseDto> {
            return Response.success(com.rknepp.parity.ledger.data.dto.ExpenseDto(id, 1L, 1L, 1000, "reversed", null, 1L, "2024-01-01T00:00:00Z", "confirmed", "2024-01-01T00:00:00Z", 1L, null, null, null, id, emptyList()))
        }
        override suspend fun listPayments(relationshipId: Long, limit: Int, offset: Int): Response<com.rknepp.parity.ledger.data.dto.PaymentListResponse> {
            return Response.success(com.rknepp.parity.ledger.data.dto.PaymentListResponse(emptyList(), 0, 100, 0))
        }
        override suspend fun createPayment(request: com.rknepp.parity.ledger.data.dto.CreatePaymentRequest): Response<com.rknepp.parity.ledger.data.dto.PaymentDto> {
            TODO("Not yet implemented")
        }
        override suspend fun confirmPayment(id: Long): Response<com.rknepp.parity.ledger.data.dto.PaymentDto> {
            return Response.success(com.rknepp.parity.ledger.data.dto.PaymentDto(id, 1L, 1L, 2L, 500, "payment", 1L, "2024-01-01T00:00:00Z", "confirmed", "2024-01-01T00:00:00Z", 1L, null, null, null, null))
        }
        override suspend fun discardPayment(id: Long, request: com.rknepp.parity.ledger.data.dto.DiscardRequest): Response<com.rknepp.parity.ledger.data.dto.PaymentDto> {
            return Response.success(com.rknepp.parity.ledger.data.dto.PaymentDto(id, 1L, 1L, 2L, 500, "payment", 1L, "2024-01-01T00:00:00Z", "discarded", null, null, "2024-01-01T00:00:00Z", 1L, null, null))
        }
        override suspend fun reversePayment(id: Long): Response<com.rknepp.parity.ledger.data.dto.PaymentDto> {
            return Response.success(com.rknepp.parity.ledger.data.dto.PaymentDto(id, 1L, 1L, 2L, 500, "reversed", 1L, "2024-01-01T00:00:00Z", "confirmed", "2024-01-01T00:00:00Z", 1L, null, null, null, id))
        }
        override suspend fun listExpenseComments(id: Long): Response<com.rknepp.parity.ledger.data.dto.CommentListResponse> {
            return Response.success(com.rknepp.parity.ledger.data.dto.CommentListResponse(emptyList()))
        }
        override suspend fun createExpenseComment(id: Long, request: com.rknepp.parity.ledger.data.dto.CreateCommentRequest): Response<com.rknepp.parity.ledger.data.dto.CommentDto> {
            return Response.success(com.rknepp.parity.ledger.data.dto.CommentDto(1L, 1L, id, null, request.content, "2024-01-01T00:00:00Z"))
        }
        override suspend fun listPaymentComments(id: Long): Response<com.rknepp.parity.ledger.data.dto.CommentListResponse> {
            return Response.success(com.rknepp.parity.ledger.data.dto.CommentListResponse(emptyList()))
        }
        override suspend fun createPaymentComment(id: Long, request: com.rknepp.parity.ledger.data.dto.CreateCommentRequest): Response<com.rknepp.parity.ledger.data.dto.CommentDto> {
            return Response.success(com.rknepp.parity.ledger.data.dto.CommentDto(2L, 1L, null, id, request.content, "2024-01-01T00:00:00Z"))
        }
    }

    private val meRepo = MeRepository { fakeAuthApi }
    private val relRepo = RelationshipRepository { fakeRelationshipApi }
    private val ledgerRepo = com.rknepp.parity.ledger.data.LedgerRepository { fakeLedgerApi }

    @Test
    fun loadSuccessReturnsLoadedStateWithBalances() = runTest {
        fakeAuthApi.meResponse = Response.success(
            UserSummary(id = 1, username = "alice", display_name = "Alice")
        )
        fakeRelationshipApi.getResponse = Response.success(
            RelationshipDto(
                id = 1,
                inviting_user = UserSummary(id = 1, username = "alice", display_name = "Alice"),
                invited_user = UserSummary(id = 2, username = "bob", display_name = "Bob"),
                status = "accepted",
                currency_code = "USD",
                created_at = "2024-01-01T00:00:00Z"
            )
        )
        fakeRelationshipApi.balanceResponse = Response.success(
            BalanceResponse(
                relationship_id = 1,
                confirmed = BalanceViewDto(net_cents = 5000, from_user_id = 2, to_user_id = 1), // Bob owes Alice 50.00
                projected = BalanceViewDto(net_cents = 2500, from_user_id = 1, to_user_id = 2), // Alice owes Bob 25.00
            )
        )

        val viewModel = RelationshipDetailViewModel(1L, relRepo, ledgerRepo, meRepo)

        viewModel.state.test {
            val state = awaitItem()
            assertTrue(state is RelationshipDetailState.Loaded)
            val data = (state as RelationshipDetailState.Loaded).data
            
            assertEquals("Bob", data.counterpartyName)
            assertEquals("bob", data.counterpartyUsername)
            assertEquals("accepted", data.status)
            assertEquals("USD", data.currencyCode)
            
            // Confirmed: Bob owes Alice -> You (Alice) don't owe
            assertFalse(data.confirmed.settled)
            assertFalse(data.confirmed.youOwe)
            assertEquals("50.00 USD", data.confirmed.amountText)
            
            // Projected: Alice owes Bob -> You (Alice) owe
            assertFalse(data.projected.settled)
            assertTrue(data.projected.youOwe)
            assertEquals("25.00 USD", data.projected.amountText)
        }
    }

    @Test
    fun loadSuccessZeroBalanceReturnsSettled() = runTest {
        fakeAuthApi.meResponse = Response.success(
            UserSummary(id = 1, username = "alice", display_name = "Alice")
        )
        fakeRelationshipApi.getResponse = Response.success(
            RelationshipDto(
                id = 1,
                inviting_user = UserSummary(id = 1, username = "alice", display_name = "Alice"),
                invited_user = UserSummary(id = 2, username = "bob", display_name = "Bob"),
                status = "accepted",
                currency_code = "USD",
                created_at = "2024-01-01T00:00:00Z"
            )
        )
        fakeRelationshipApi.balanceResponse = Response.success(
            BalanceResponse(
                relationship_id = 1,
                confirmed = BalanceViewDto(net_cents = 0, from_user_id = null, to_user_id = null),
                projected = BalanceViewDto(net_cents = 0, from_user_id = null, to_user_id = null),
            )
        )

        val viewModel = RelationshipDetailViewModel(1L, relRepo, ledgerRepo, meRepo)

        viewModel.state.test {
            val state = awaitItem()
            assertTrue(state is RelationshipDetailState.Loaded)
            val data = (state as RelationshipDetailState.Loaded).data
            
            assertTrue(data.confirmed.settled)
            assertEquals("0.00 USD", data.confirmed.amountText)
            
            assertTrue(data.projected.settled)
            assertEquals("0.00 USD", data.projected.amountText)
        }
    }

    @Test
    fun loadErrorFromMeReturnsErrorState() = runTest {
        fakeAuthApi.meResponse = Response.error(401, okhttp3.ResponseBody.create(null, ""))
        
        val viewModel = RelationshipDetailViewModel(1L, relRepo, ledgerRepo, meRepo)

        viewModel.state.test {
            val state = awaitItem()
            assertTrue(state is RelationshipDetailState.Error)
        }
    }

    @Test
    fun loadErrorFromGetReturnsErrorState() = runTest {
        fakeAuthApi.meResponse = Response.success(
            UserSummary(id = 1, username = "alice", display_name = "Alice")
        )
        fakeRelationshipApi.getResponse = Response.error(500, okhttp3.ResponseBody.create(null, ""))
        
        val viewModel = RelationshipDetailViewModel(1L, relRepo, ledgerRepo, meRepo)

        viewModel.state.test {
            val state = awaitItem()
            assertTrue(state is RelationshipDetailState.Error)
        }
    }

    @Test
    fun loadErrorFromBalanceReturnsErrorState() = runTest {
        fakeAuthApi.meResponse = Response.success(
            UserSummary(id = 1, username = "alice", display_name = "Alice")
        )
        fakeRelationshipApi.getResponse = Response.success(
            RelationshipDto(
                id = 1,
                inviting_user = UserSummary(id = 1, username = "alice", display_name = "Alice"),
                invited_user = UserSummary(id = 2, username = "bob", display_name = "Bob"),
                status = "accepted",
                currency_code = "USD",
                created_at = "2024-01-01T00:00:00Z"
            )
        )
        fakeRelationshipApi.balanceResponse = Response.error(500, okhttp3.ResponseBody.create(null, ""))
        
        val viewModel = RelationshipDetailViewModel(1L, relRepo, ledgerRepo, meRepo)

        viewModel.state.test {
            val state = awaitItem()
            assertTrue(state is RelationshipDetailState.Error)
        }
    }
}
