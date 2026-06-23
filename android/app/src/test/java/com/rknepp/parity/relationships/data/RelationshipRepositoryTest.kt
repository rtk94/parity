package com.rknepp.parity.relationships.data

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.rknepp.parity.network.ApiResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit

class RelationshipRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repo: RelationshipRepository

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }

        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient.Builder().build())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        val api = retrofit.create(RelationshipApi::class.java)
        repo = RelationshipRepository(apiProvider = { api })
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun listSuccessReturnsListResponse() = runBlocking {
        val body = """
            {
                "items": [
                    {
                        "id": 1,
                        "inviting_user": {"id": 1, "username": "alice", "display_name": "Alice"},
                        "invited_user": {"id": 2, "username": "bob", "display_name": "Bob"},
                        "status": "accepted",
                        "currency_code": "USD",
                        "created_at": "2024-01-01T00:00:00Z"
                    }
                ],
                "total": 1,
                "limit": 10,
                "offset": 0
            }
        """.trimIndent()
        server.enqueue(jsonResponse(200, body))

        val result = repo.list()
        assertTrue(result is ApiResult.Success)
        val data = (result as ApiResult.Success).data
        assertEquals(1, data.total)
        assertEquals(1, data.items.size)
        assertEquals(1L, data.items[0].id)
        assertEquals("accepted", data.items[0].status)
    }

    @Test
    fun getSuccessReturnsRelationship() = runBlocking {
        val body = """
            {
                "id": 2,
                "inviting_user": {"id": 1, "username": "alice", "display_name": "Alice"},
                "invited_user": {"id": 3, "username": "carol", "display_name": "Carol"},
                "status": "pending",
                "currency_code": "EUR",
                "created_at": "2024-01-01T00:00:00Z"
            }
        """.trimIndent()
        server.enqueue(jsonResponse(200, body))

        val result = repo.get(2L)
        assertTrue(result is ApiResult.Success)
        val data = (result as ApiResult.Success).data
        assertEquals(2L, data.id)
        assertEquals("pending", data.status)
        assertEquals("EUR", data.currencyCode)
    }

    @Test
    fun balanceSuccessReturnsBalanceResponse() = runBlocking {
        val body = """
            {
                "relationship_id": 1,
                "confirmed": {"net_cents": 5000, "from_user_id": 2, "to_user_id": 1},
                "projected": {"net_cents": 2500, "from_user_id": 2, "to_user_id": 1}
            }
        """.trimIndent()
        server.enqueue(jsonResponse(200, body))

        val result = repo.balance(1L)
        assertTrue(result is ApiResult.Success)
        val data = (result as ApiResult.Success).data
        assertEquals(1L, data.relationshipId)
        assertEquals(5000L, data.confirmed.netCents)
        assertEquals(2L, data.confirmed.fromUserId)
        assertEquals(1L, data.confirmed.toUserId)
    }

    private fun jsonResponse(code: Int, body: String): MockResponse =
        MockResponse()
            .setResponseCode(code)
            .setHeader("Content-Type", "application/json")
            .setBody(body)
}
