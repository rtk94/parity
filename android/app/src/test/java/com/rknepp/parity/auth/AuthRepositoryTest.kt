package com.rknepp.parity.auth

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadConfig
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.rknepp.parity.auth.data.AuthApi
import com.rknepp.parity.auth.data.AuthRepository
import com.rknepp.parity.network.ApiResult
import com.rknepp.parity.storage.SecureTokenStore
import com.rknepp.parity.storage.TinkAeadProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Retrofit

class AuthRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var tokenStore: SecureTokenStore
    private lateinit var repo: AuthRepository

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Before
    fun setUp() {
        AeadConfig.register()
        server = MockWebServer().apply { start() }

        val handle = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))
        val aead = handle.getPrimitive(Aead::class.java)
        val provider = object : TinkAeadProvider { override fun aead(): Aead = aead }
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            produceFile = { tempFolder.newFile("secure.preferences_pb") },
        )
        tokenStore = SecureTokenStore.forTesting(dataStore, provider)

        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient.Builder().build())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        val api = retrofit.create(AuthApi::class.java)
        repo = AuthRepository(authApiProvider = { api }, tokenStore = tokenStore)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun loginSuccessStoresTokenAndReturnsUser() = runBlocking {
        val body = """
            {"token":"tok-123","user":{"id":1,"username":"alice","display_name":"Alice"}}
        """.trimIndent()
        server.enqueue(jsonResponse(200, body))

        val result = repo.login("alice", "pw")

        assertTrue(result is ApiResult.Success)
        val user = (result as ApiResult.Success).data
        assertEquals("alice", user.username)
        assertEquals(1L, user.id)
        assertEquals("tok-123", tokenStore.token.first())
    }

    @Test
    fun loginBadCredentialsReturnsParsedHttpFailure() = runBlocking {
        val body = """
            {"error":{"code":"invalid_credentials","message":"Invalid username or password."}}
        """.trimIndent()
        server.enqueue(jsonResponse(401, body))

        val result = repo.login("alice", "wrong")

        assertTrue(result is ApiResult.HttpFailure)
        val fail = result as ApiResult.HttpFailure
        assertEquals(401, fail.code)
        assertNotNull(fail.error)
        assertEquals("invalid_credentials", fail.error?.code)
        assertNull(tokenStore.token.first())
    }

    @Test
    fun registerSuccessReturnsUser() = runBlocking {
        val body = """{"id":7,"username":"carol","display_name":"Carol"}"""
        server.enqueue(jsonResponse(201, body))

        val result = repo.register("carol", "pw", "Carol")
        assertTrue(result is ApiResult.Success)
        assertEquals(7L, (result as ApiResult.Success).data.id)
    }

    @Test
    fun registerWithEmailIncludesEmailInBody() = runBlocking {
        server.enqueue(jsonResponse(201, """{"id":8,"username":"dave","display_name":"Dave"}"""))

        val result = repo.register("dave", "pw", "Dave", "dave@example.com")

        assertTrue(result is ApiResult.Success)
        val sent = server.takeRequest().body.readUtf8()
        assertTrue(sent.contains("\"email\":\"dave@example.com\""))
    }

    @Test
    fun registerWithoutEmailOmitsEmailFromBody() = runBlocking {
        server.enqueue(jsonResponse(201, """{"id":9,"username":"erin","display_name":"Erin"}"""))

        // No email argument -> null -> omitted from JSON (explicitNulls =
        // false), so the backend treats it as "no recovery address".
        val result = repo.register("erin", "pw", "Erin")

        assertTrue(result is ApiResult.Success)
        val sent = server.takeRequest().body.readUtf8()
        assertTrue("email must be absent, was: $sent", !sent.contains("email"))
    }

    @Test
    fun registerEmailConflictSurfacesParsedFailure() = runBlocking {
        val body = """{"error":{"code":"email_taken","message":"Email is already in use."}}"""
        server.enqueue(jsonResponse(409, body))

        val result = repo.register("frank", "pw", "Frank", "taken@example.com")

        assertTrue(result is ApiResult.HttpFailure)
        val fail = result as ApiResult.HttpFailure
        assertEquals(409, fail.code)
        assertEquals("email_taken", fail.error?.code)
    }

    @Test
    fun registerConflictReturnsParsedUsernameTaken() = runBlocking {
        val body = """
            {"error":{"code":"username_taken","message":"Username is already taken."}}
        """.trimIndent()
        server.enqueue(jsonResponse(409, body))

        val result = repo.register("carol", "pw", "Carol")
        assertTrue(result is ApiResult.HttpFailure)
        val fail = result as ApiResult.HttpFailure
        assertEquals(409, fail.code)
        assertEquals("username_taken", fail.error?.code)
    }

    @Test
    fun logoutClearsTokenEvenOnBackendNetworkError() = runBlocking {
        // Seed a token, then simulate network failure on the
        // /auth/logout call.
        tokenStore.set("seeded-token")
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        val result = repo.logout()

        assertTrue(result is ApiResult.NetworkFailure)
        assertNull(tokenStore.token.first())
    }

    @Test
    fun deleteAccountSuccessClearsToken() = runBlocking {
        tokenStore.set("seeded-token")
        // 204 No Content — the anonymizing delete succeeded.
        server.enqueue(MockResponse().setResponseCode(204))

        val result = repo.deleteAccount("pw-alice")

        assertTrue(result is ApiResult.Success)
        assertNull(tokenStore.token.first())
    }

    @Test
    fun deleteAccountWrongPasswordKeepsToken() = runBlocking {
        tokenStore.set("seeded-token")
        val body = """
            {"error":{"code":"invalid_password","message":"Password confirmation is required to delete your account."}}
        """.trimIndent()
        server.enqueue(jsonResponse(403, body))

        val result = repo.deleteAccount("wrong")

        assertTrue(result is ApiResult.HttpFailure)
        assertEquals("invalid_password", (result as ApiResult.HttpFailure).error?.code)
        // The account is untouched, so the local session must survive.
        assertEquals("seeded-token", tokenStore.token.first())
    }

    @Test
    fun requestPasswordResetSendsEmailAndSucceedsOn204() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))

        val result = repo.requestPasswordReset("alice@example.com")

        assertTrue(result is ApiResult.Success)
        val recorded = server.takeRequest()
        assertEquals("/api/v1/auth/password-reset/request", recorded.path)
        assertTrue(recorded.body.readUtf8().contains("\"email\":\"alice@example.com\""))
    }

    @Test
    fun confirmPasswordResetSendsTokenAndNewPasswordAndSucceedsOn204() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))

        val result = repo.confirmPasswordReset("tok-abc", "brandnewpass")

        assertTrue(result is ApiResult.Success)
        val recorded = server.takeRequest()
        assertEquals("/api/v1/auth/password-reset/confirm", recorded.path)
        val sent = recorded.body.readUtf8()
        assertTrue(sent.contains("\"token\":\"tok-abc\""))
        assertTrue(sent.contains("\"new_password\":\"brandnewpass\""))
    }

    @Test
    fun confirmPasswordResetWeakPasswordSurfacesParsedFailure() = runBlocking {
        val body = """
            {"error":{"code":"weak_password","message":"new_password is too short.","details":{"min_length":8}}}
        """.trimIndent()
        server.enqueue(jsonResponse(422, body))

        val result = repo.confirmPasswordReset("tok-abc", "short")

        assertTrue(result is ApiResult.HttpFailure)
        val fail = result as ApiResult.HttpFailure
        assertEquals(422, fail.code)
        assertEquals("weak_password", fail.error?.code)
    }

    @Test
    fun verifyServerUrlSuccess() = runBlocking {
        server.enqueue(jsonResponse(200, """{"status":"ok","database":"ok"}"""))
        val result = repo.verifyServerUrl(server.url("/").toString().trimEnd('/'))
        assertTrue(result is ApiResult.Success)
        assertEquals("ok", (result as ApiResult.Success).data.status)
    }

    @Test
    fun verifyServerUrlOnNonParityResponseSurfacesFailure() = runBlocking {
        // 200 but the body doesn't match HealthResponse. With
        // ignoreUnknownKeys = true and missing required fields,
        // kotlinx.serialization throws — mapped to UnexpectedFailure.
        server.enqueue(jsonResponse(200, """{"unrelated":"thing"}"""))
        val result = repo.verifyServerUrl(server.url("/").toString().trimEnd('/'))
        assertTrue(
            "expected UnexpectedFailure, got $result",
            result is ApiResult.UnexpectedFailure,
        )
    }

    private fun jsonResponse(code: Int, body: String): MockResponse =
        MockResponse()
            .setResponseCode(code)
            .setHeader("Content-Type", "application/json")
            .setBody(body)
}
