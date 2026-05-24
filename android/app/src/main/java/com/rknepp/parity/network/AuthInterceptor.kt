package com.rknepp.parity.network

import com.rknepp.parity.storage.SecureTokenStore
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Attaches the stored bearer token to outgoing requests when one is
 * available. Does not skip specific endpoints: callers without a token
 * (login, register, health) simply have no header added, and a retry
 * driven by [TokenAuthenticator] sets its own Authorization header
 * which must not be overwritten.
 */
class AuthInterceptor(
    private val tokenStore: SecureTokenStore,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header(HEADER_AUTHORIZATION) != null) {
            return chain.proceed(request)
        }
        val token = runBlocking { tokenStore.token.firstOrNull() }
        val finalRequest = if (token.isNullOrEmpty()) {
            request
        } else {
            request.newBuilder()
                .header(HEADER_AUTHORIZATION, "Bearer $token")
                .build()
        }
        return chain.proceed(finalRequest)
    }

    companion object {
        const val HEADER_AUTHORIZATION = "Authorization"
    }
}
