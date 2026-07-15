package com.rknepp.parity.ledger.data

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

class LedgerRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repo: LedgerRepository

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient.Builder().build())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        val api = retrofit.create(LedgerApi::class.java)
        repo = LedgerRepository { api }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun listAttachmentsParsesItems() = runBlocking {
        val body = """
            {"items":[{"id":3,"expense_id":7,"uploaded_by_user_id":1,"filename":"receipt.pdf",
            "content_type":"application/pdf","size_bytes":1024,"checksum_sha256":"abc","created_at":"2024-01-01T00:00:00Z"}]}
        """.trimIndent()
        server.enqueue(jsonResponse(200, body))

        val result = repo.listAttachments(7)

        assertTrue(result is ApiResult.Success)
        val items = (result as ApiResult.Success).data.items
        assertEquals(1, items.size)
        assertEquals("receipt.pdf", items[0].filename)
        assertEquals("/api/v1/expenses/7/attachments", server.takeRequest().path)
    }

    @Test
    fun uploadAttachmentSendsMultipartFilePart() = runBlocking {
        val body = """
            {"id":9,"expense_id":7,"uploaded_by_user_id":1,"filename":"a.png","content_type":"image/png",
            "size_bytes":4,"checksum_sha256":"x","created_at":"2024-01-01T00:00:00Z"}
        """.trimIndent()
        server.enqueue(jsonResponse(201, body))

        val result = repo.uploadAttachment(7, "a.png", "image/png", byteArrayOf(1, 2, 3, 4))

        assertTrue(result is ApiResult.Success)
        assertEquals(9L, (result as ApiResult.Success).data.id)
        val recorded = server.takeRequest()
        assertEquals("/api/v1/expenses/7/attachments", recorded.path)
        assertTrue(recorded.headers["Content-Type"]?.startsWith("multipart/form-data") == true)
        val sent = recorded.body.readUtf8()
        // The backend requires the part name "file"; the display filename rides along too.
        assertTrue("missing file part name", sent.contains("name=\"file\""))
        assertTrue("missing filename", sent.contains("filename=\"a.png\""))
    }

    @Test
    fun downloadAttachmentReturnsBytes() = runBlocking {
        val bytes = byteArrayOf(10, 20, 30, 40, 50)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/octet-stream")
                .setBody(okio.Buffer().write(bytes)),
        )

        val result = repo.downloadAttachment(9)

        assertTrue(result is ApiResult.Success)
        assertTrue(bytes.contentEquals((result as ApiResult.Success).data))
        assertEquals("/api/v1/attachments/9", server.takeRequest().path)
    }

    @Test
    fun deleteAttachmentSucceedsOn204() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))

        val result = repo.deleteAttachment(9)

        assertTrue(result is ApiResult.Success)
        val recorded = server.takeRequest()
        assertEquals("DELETE", recorded.method)
        assertEquals("/api/v1/attachments/9", recorded.path)
    }

    @Test
    fun deleteAttachmentNonUploaderReturnsParsedFailure() = runBlocking {
        val body = """{"error":{"code":"forbidden","message":"Only the uploader can delete this."}}"""
        server.enqueue(jsonResponse(403, body))

        val result = repo.deleteAttachment(9)

        assertTrue(result is ApiResult.HttpFailure)
        assertEquals(403, (result as ApiResult.HttpFailure).code)
        assertEquals("forbidden", result.error?.code)
    }

    private fun jsonResponse(code: Int, body: String): MockResponse =
        MockResponse()
            .setResponseCode(code)
            .setHeader("Content-Type", "application/json")
            .setBody(body)
}
