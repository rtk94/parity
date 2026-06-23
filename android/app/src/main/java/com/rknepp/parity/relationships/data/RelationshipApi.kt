package com.rknepp.parity.relationships.data

import com.rknepp.parity.relationships.data.dto.BalanceResponse
import com.rknepp.parity.relationships.data.dto.CreateRelationshipRequest
import com.rknepp.parity.relationships.data.dto.RelationshipDto
import com.rknepp.parity.relationships.data.dto.RelationshipListResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface RelationshipApi {
    @GET("api/v1/relationships")
    suspend fun list(
        @Query("status") status: String? = null,
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null,
    ): Response<RelationshipListResponse>

    @GET("api/v1/relationships/{id}")
    suspend fun get(@Path("id") id: Long): Response<RelationshipDto>

    @GET("api/v1/relationships/{id}/balance")
    suspend fun balance(@Path("id") id: Long): Response<BalanceResponse>

    @POST("api/v1/relationships")
    suspend fun create(@Body request: CreateRelationshipRequest): Response<RelationshipDto>

    @POST("api/v1/relationships/{id}/accept")
    suspend fun accept(@Path("id") id: Long): Response<RelationshipDto>

    @POST("api/v1/relationships/{id}/reject")
    suspend fun reject(@Path("id") id: Long): Response<RelationshipDto>
}
