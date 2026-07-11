package com.rknepp.parity.auth.data

import com.rknepp.parity.auth.data.dto.DeleteAccountRequest
import com.rknepp.parity.auth.data.dto.LoginRequest
import com.rknepp.parity.auth.data.dto.RegisterRequest
import com.rknepp.parity.home.model.UserSummary
import com.rknepp.parity.network.ApiResult
import com.rknepp.parity.network.HealthApi
import com.rknepp.parity.network.HealthResponse
import com.rknepp.parity.network.RetrofitFactory
import com.rknepp.parity.network.apiCall
import com.rknepp.parity.network.apiCallUnit
import com.rknepp.parity.storage.SecureTokenStore

/**
 * Auth-side repository. Calls return [ApiResult] and never throw.
 *
 * Token state is owned by [SecureTokenStore]; this class writes on
 * login success and clears unconditionally on logout (success or
 * failure of the backend round trip).
 */
class AuthRepository(
    private val authApiProvider: () -> AuthApi,
    private val tokenStore: SecureTokenStore,
) {

    suspend fun register(
        username: String,
        password: String,
        displayName: String,
    ): ApiResult<UserSummary> = apiCall {
        authApiProvider().register(RegisterRequest(username, password, displayName))
    }

    suspend fun login(username: String, password: String): ApiResult<UserSummary> =
        when (val result = apiCall { authApiProvider().login(LoginRequest(username, password)) }) {
            is ApiResult.Success -> {
                tokenStore.set(result.data.token)
                ApiResult.Success(result.data.user)
            }
            is ApiResult.HttpFailure -> result
            is ApiResult.NetworkFailure -> result
            is ApiResult.UnexpectedFailure -> result
        }

    suspend fun logout(): ApiResult<Unit> {
        val result = apiCallUnit { authApiProvider().logout() }
        // Local clear is unconditional: the user is logging out, and a
        // stranded server-side row is preferable to keeping a local
        // session the user already abandoned.
        tokenStore.clear()
        return result
    }

    /**
     * Deletes (anonymizes) the caller's account. The password is
     * re-confirmed server-side; a wrong password returns 403 and
     * changes nothing, so the local token is cleared only on success —
     * the server has revoked every token by then.
     */
    suspend fun deleteAccount(password: String): ApiResult<Unit> {
        val result = apiCallUnit { authApiProvider().deleteAccount(DeleteAccountRequest(password)) }
        if (result is ApiResult.Success) {
            tokenStore.clear()
        }
        return result
    }

    /**
     * Validates a candidate server URL by hitting /api/v1/health.
     * Uses a one-off Retrofit instance — the locator's current
     * Retrofit is not touched until the URL is persisted.
     */
    suspend fun verifyServerUrl(url: String): ApiResult<HealthResponse> {
        val client = RetrofitFactory.bareClient()
        val retrofit = RetrofitFactory.retrofit(url, client)
        val api = retrofit.create(HealthApi::class.java)
        return apiCall { api.health() }
    }
}
