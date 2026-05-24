package com.rknepp.parity.home.data

import com.rknepp.parity.auth.data.AuthApi
import com.rknepp.parity.home.model.UserSummary
import com.rknepp.parity.network.ApiResult
import com.rknepp.parity.network.apiCall

class MeRepository(
    private val authApiProvider: () -> AuthApi,
) {
    suspend fun fetchMe(): ApiResult<UserSummary> = apiCall {
        authApiProvider().me()
    }
}
