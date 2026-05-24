package com.rknepp.parity.network

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.GET

@Serializable
data class HealthResponse(
    val status: String,
    val database: String,
)

interface HealthApi {
    @GET("api/v1/health")
    suspend fun health(): Response<HealthResponse>
}
