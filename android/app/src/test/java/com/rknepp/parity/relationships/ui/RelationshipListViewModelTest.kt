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
import com.rknepp.parity.relationships.data.dto.RelationshipDto
import com.rknepp.parity.relationships.data.dto.RelationshipListResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import retrofit2.Response

class RelationshipListViewModelTest {

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
        override suspend fun exportData() = error("unused")
        override suspend fun deleteAccount(body: com.rknepp.parity.auth.data.dto.DeleteAccountRequest) = error("unused")
        override suspend fun registerDevice(body: com.rknepp.parity.auth.data.dto.RegisterDeviceRequest) = error("unused")
        override suspend fun unregisterDevice(body: com.rknepp.parity.auth.data.dto.UnregisterDeviceRequest) = error("unused")
    }

    private val fakeRelationshipApi = object : RelationshipApi {
        var listResponse: Response<RelationshipListResponse>? = null

        // Balance failures degrade to a null netCentsForMe, so tests
        // that don't set this still exercise the list path.
        var balanceResponse: Response<BalanceResponse>? = null
        override suspend fun list(status: String?, limit: Int?, offset: Int?) = listResponse ?: error("not set")
        override suspend fun get(id: Long) = error("unused")
        override suspend fun balance(id: Long) = balanceResponse ?: error("not set")
        override suspend fun create(request: com.rknepp.parity.relationships.data.dto.CreateRelationshipRequest) = error("unused")
        override suspend fun accept(id: Long) = error("unused")
        override suspend fun reject(id: Long) = error("unused")
    }

    private val meRepo = MeRepository { fakeAuthApi }
    private val relRepo = RelationshipRepository { fakeRelationshipApi }

    @Test
    fun loadSuccessReturnsLoadedState() = runTest {
        fakeAuthApi.meResponse = Response.success(
            UserSummary(id = 1, username = "alice", display_name = "Alice")
        )
        fakeRelationshipApi.listResponse = Response.success(
            RelationshipListResponse(
                items = listOf(
                    RelationshipDto(
                        id = 1,
                        inviting_user = UserSummary(id = 1, username = "alice", display_name = "Alice"),
                        invited_user = UserSummary(id = 2, username = "bob", display_name = "Bob"),
                        status = "accepted",
                        currency_code = "USD",
                        created_at = "2024-01-01T00:00:00Z"
                    )
                ),
                total = 1,
                limit = 10,
                offset = 0
            )
        )
        fakeRelationshipApi.balanceResponse = Response.success(
            BalanceResponse(
                relationship_id = 1,
                // Bob owes Alice 50.00
                confirmed = com.rknepp.parity.relationships.data.dto.BalanceViewDto(
                    net_cents = 5000, from_user_id = 2, to_user_id = 1,
                ),
                projected = com.rknepp.parity.relationships.data.dto.BalanceViewDto(
                    net_cents = 5000, from_user_id = 2, to_user_id = 1,
                ),
            )
        )

        val viewModel = RelationshipListViewModel(relRepo, meRepo)

        viewModel.state.test {
            val state = awaitItem()
            assertTrue(state is RelationshipListState.Loaded)
            val rows = (state as RelationshipListState.Loaded).rows
            assertEquals(1, rows.size)
            assertEquals("Bob", rows[0].counterpartyName)
            assertEquals("bob", rows[0].counterpartyUsername)
            assertEquals("accepted", rows[0].status)
            assertEquals("USD", rows[0].currencyCode)
            // Confirmed balance resolved from the caller's viewpoint.
            assertEquals(5000L, rows[0].netCentsForMe)
        }
    }

    @Test
    fun loadEmptyReturnsEmptyState() = runTest {
        fakeAuthApi.meResponse = Response.success(
            UserSummary(id = 1, username = "alice", display_name = "Alice")
        )
        fakeRelationshipApi.listResponse = Response.success(
            RelationshipListResponse(
                items = emptyList(),
                total = 0,
                limit = 10,
                offset = 0
            )
        )

        val viewModel = RelationshipListViewModel(relRepo, meRepo)

        viewModel.state.test {
            val state = awaitItem()
            assertTrue(state is RelationshipListState.Empty)
        }
    }

    @Test
    fun loadErrorFromMeReturnsErrorState() = runTest {
        fakeAuthApi.meResponse = Response.error(401, okhttp3.ResponseBody.create(null, ""))
        val viewModel = RelationshipListViewModel(relRepo, meRepo)

        viewModel.state.test {
            val state = awaitItem()
            assertTrue(state is RelationshipListState.Error)
        }
    }

    @Test
    fun loadErrorFromListReturnsErrorState() = runTest {
        fakeAuthApi.meResponse = Response.success(
            UserSummary(id = 1, username = "alice", display_name = "Alice")
        )
        fakeRelationshipApi.listResponse = Response.error(500, okhttp3.ResponseBody.create(null, ""))

        val viewModel = RelationshipListViewModel(relRepo, meRepo)

        viewModel.state.test {
            val state = awaitItem()
            assertTrue(state is RelationshipListState.Error)
        }
    }
}
