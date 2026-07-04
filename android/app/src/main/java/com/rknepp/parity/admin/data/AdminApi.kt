package com.rknepp.parity.admin.data

import com.rknepp.parity.admin.data.dto.AdminStatsDto
import com.rknepp.parity.admin.data.dto.CleanupTokensResponse
import com.rknepp.parity.admin.data.dto.ResetLedgerRequest
import com.rknepp.parity.admin.data.dto.ResetLedgerResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

interface AdminApi {
    @GET("api/v1/admin/stats")
    suspend fun stats(): Response<AdminStatsDto>

    @POST("api/v1/admin/cleanup-tokens")
    suspend fun cleanupTokens(): Response<CleanupTokensResponse>

    @POST("api/v1/admin/reset-ledger")
    suspend fun resetLedger(@Body body: ResetLedgerRequest): Response<ResetLedgerResponse>
}
