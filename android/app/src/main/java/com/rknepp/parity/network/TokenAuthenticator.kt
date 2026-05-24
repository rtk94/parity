package com.rknepp.parity.network

import com.rknepp.parity.auth.events.AuthEvent
import com.rknepp.parity.auth.events.AuthEventBus
import com.rknepp.parity.storage.SecureTokenStore
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Authenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route

/**
 * Recovers from 401 responses by calling POST /auth/refresh exactly
 * once per token, then retrying the failed request with the new
 * bearer. On refresh failure, clears the local token and emits
 * [AuthEvent.SessionExpired] so the UI can route back to login.
 *
 * Construct lazily — the underlying OkHttpClient that performs the
 * refresh call is the same client that hands 401s to this
 * authenticator. The provider indirection breaks the cycle without
 * forcing OkHttpClient::build to know about the authenticator.
 */
class TokenAuthenticator(
    private val baseUrlProvider: () -> String?,
    private val refreshClientProvider: () -> okhttp3.OkHttpClient,
    private val tokenStore: SecureTokenStore,
    private val authEventBus: AuthEventBus,
) : Authenticator {

    private val refreshLock = Any()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    override fun authenticate(route: Route?, response: Response): Request? {
        val request = response.request

        // Don't retry a failing refresh — that is the terminal path.
        if (request.url.encodedPath.endsWith(REFRESH_PATH)) {
            runBlocking { tokenStore.clear() }
            authEventBus.tryEmit(AuthEvent.SessionExpired)
            return null
        }

        // Requests without an Authorization header (login, register,
        // health) must not be retried — surface the 401 to the caller.
        val failedAuth = request.header(AuthInterceptor.HEADER_AUTHORIZATION) ?: return null
        val failedToken = failedAuth.removePrefix("Bearer ").trim()

        synchronized(refreshLock) {
            val currentToken = runBlocking { tokenStore.token.firstOrNull() }

            // Another concurrent 401 already refreshed; retry with the
            // current token.
            if (!currentToken.isNullOrEmpty() && currentToken != failedToken) {
                return request.newBuilder()
                    .header(AuthInterceptor.HEADER_AUTHORIZATION, "Bearer $currentToken")
                    .build()
            }

            val baseUrl = baseUrlProvider()
            if (baseUrl.isNullOrEmpty() || currentToken.isNullOrEmpty()) {
                clearAndEmit()
                return null
            }

            val newToken = runCatching { performRefresh(baseUrl, currentToken) }
                .getOrNull()
            if (newToken.isNullOrEmpty()) {
                clearAndEmit()
                return null
            }

            runBlocking { tokenStore.set(newToken) }
            return request.newBuilder()
                .header(AuthInterceptor.HEADER_AUTHORIZATION, "Bearer $newToken")
                .build()
        }
    }

    private fun clearAndEmit() {
        runBlocking { tokenStore.clear() }
        authEventBus.tryEmit(AuthEvent.SessionExpired)
    }

    private fun performRefresh(baseUrl: String, token: String): String? {
        val normalized = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        val url = normalized + REFRESH_PATH.trimStart('/')
        val empty = "".toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(empty)
            .header(AuthInterceptor.HEADER_AUTHORIZATION, "Bearer $token")
            .build()
        refreshClientProvider().newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            val parsed = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return null
            val obj = parsed as? JsonObject ?: return null
            val tokenElement = obj["token"] ?: return null
            return runCatching { tokenElement.jsonPrimitive.content }.getOrNull()
        }
    }

    companion object {
        const val REFRESH_PATH = "/api/v1/auth/refresh"
    }
}
