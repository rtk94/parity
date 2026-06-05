package com.rknepp.parity.network

import app.cash.turbine.test
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadConfig
import com.rknepp.parity.auth.events.AuthEvent
import com.rknepp.parity.auth.events.AuthEventBus
import com.rknepp.parity.storage.SecureTokenStore
import com.rknepp.parity.storage.TinkAeadProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TokenAuthenticatorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var tokenStore: SecureTokenStore
    private lateinit var eventBus: AuthEventBus
    private lateinit var client: OkHttpClient
    private lateinit var bareClient: OkHttpClient

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
        eventBus = AuthEventBus()

        bareClient = OkHttpClient.Builder().build()
        val authInterceptor = AuthInterceptor(tokenStore)
        val authenticator = TokenAuthenticator(
            baseUrlProvider = { server.url("/").toString().trimEnd('/') },
            refreshClientProvider = { bareClient },
            tokenStore = tokenStore,
            authEventBus = eventBus,
        )
        client = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .authenticator(authenticator)
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun single401TriggersRefreshAndRetry() = runBlocking {
        tokenStore.set("old-token")

        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"token":"new-token","user":{"id":1,"username":"a","display_name":"A"}}"""),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"ok":true}"""),
        )

        val response = client.newCall(get("/api/v1/auth/me")).execute()
        response.use { assertEquals(200, it.code) }

        // Drain in order: original /me, refresh, retried /me.
        val first = takeRequest()
        assertEquals("/api/v1/auth/me", first.path)
        assertEquals("Bearer old-token", first.getHeader("Authorization"))

        val refresh = takeRequest()
        assertEquals("/api/v1/auth/refresh", refresh.path)
        assertEquals("Bearer old-token", refresh.getHeader("Authorization"))

        val retry = takeRequest()
        assertEquals("/api/v1/auth/me", retry.path)
        assertEquals("Bearer new-token", retry.getHeader("Authorization"))

        assertEquals("new-token", tokenStore.token.first())
    }

    @Test
    fun concurrent401sResultInSingleRefresh() = runBlocking {
        tokenStore.set("old-token")

        val refreshes = AtomicInteger(0)
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when {
                    request.path == "/api/v1/auth/refresh" -> {
                        refreshes.incrementAndGet()
                        MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "application/json")
                            .setBody("""{"token":"new-token","user":{"id":1,"username":"a","display_name":"A"}}""")
                    }
                    request.getHeader("Authorization") == "Bearer new-token" ->
                        MockResponse().setResponseCode(200).setBody("ok")
                    else ->
                        MockResponse().setResponseCode(401)
                }
            }
        }

        val n = 6
        val pool = Executors.newFixedThreadPool(n)
        val latch = CountDownLatch(n)
        val results = mutableListOf<Int>()
        repeat(n) {
            pool.submit {
                try {
                    val resp = client.newCall(get("/api/v1/auth/me")).execute()
                    synchronized(results) { results.add(resp.code) }
                    resp.close()
                } finally {
                    latch.countDown()
                }
            }
        }
        assertTrue(latch.await(15, TimeUnit.SECONDS))
        pool.shutdownNow()

        assertEquals(n, results.size)
        assertTrue("expected all retries to succeed: $results", results.all { it == 200 })
        assertEquals(1, refreshes.get())
        assertEquals("new-token", tokenStore.token.first())
    }

    @Test
    fun refreshFailureClearsTokenAndEmitsSessionExpired() = runBlocking {
        tokenStore.set("old-token")
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(401))

        eventBus.events.test {
            val response = client.newCall(get("/api/v1/auth/me")).execute()
            response.use { assertEquals(401, it.code) }

            val emitted = awaitItem()
            assertTrue(emitted is AuthEvent.SessionExpired)
            cancelAndIgnoreRemainingEvents()
        }

        assertNull(tokenStore.token.first())
    }

    @Test
    fun unauthenticatedRequestIsNotRetried() = runBlocking {
        // No token stored; auth interceptor will not add a header.
        server.enqueue(MockResponse().setResponseCode(401))

        val response = client.newCall(get("/api/v1/auth/login")).execute()
        response.use { assertEquals(401, it.code) }

        // Only the original request — no refresh call.
        assertEquals(1, server.requestCount)
    }

    @Test
    fun refreshEndpoint401IsNotRetried() = runBlocking {
        tokenStore.set("old-token")
        server.enqueue(MockResponse().setResponseCode(401))

        // Directly call /auth/refresh with an Authorization header so
        // the authenticator's path-suffix check kicks in.
        val response = client.newCall(get("/api/v1/auth/refresh")).execute()
        response.use { assertEquals(401, it.code) }

        assertEquals(1, server.requestCount)
        assertNull(tokenStore.token.first())
    }

    private fun get(path: String): Request =
        Request.Builder().url(server.url(path)).get().build()

    private fun takeRequest(): RecordedRequest =
        server.takeRequest(5, TimeUnit.SECONDS) ?: error("no request received")
}
