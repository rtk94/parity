package com.rknepp.parity.home.data

import com.rknepp.parity.auth.data.AuthApi
import com.rknepp.parity.home.model.UserSummary
import com.rknepp.parity.network.ApiResult
import com.rknepp.parity.network.apiCall
import kotlinx.serialization.json.JsonElement

class MeRepository(
    private val authApiProvider: () -> AuthApi,
) {
    suspend fun fetchMe(): ApiResult<UserSummary> = apiCall {
        authApiProvider().me()
    }

    suspend fun updateProfile(request: com.rknepp.parity.auth.data.dto.UpdateProfileRequest): ApiResult<UserSummary> = apiCall {
        authApiProvider().updateProfile(request)
    }

    suspend fun changePassword(request: com.rknepp.parity.auth.data.dto.ChangePasswordRequest): ApiResult<Unit> = apiCall {
        authApiProvider().changePassword(request)
    }

    /** Full machine-readable dump of the caller's account, as raw JSON. */
    suspend fun exportData(): ApiResult<JsonElement> = apiCall {
        authApiProvider().exportData()
    }
}
