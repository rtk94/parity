package com.rknepp.parity.auth.data

import com.rknepp.parity.auth.data.dto.LoginRequest
import com.rknepp.parity.auth.data.dto.LoginResponse
import com.rknepp.parity.auth.data.dto.RegisterRequest
import com.rknepp.parity.home.model.UserSummary
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

interface AuthApi {
    @POST("api/v1/auth/register")
    suspend fun register(@Body body: RegisterRequest): Response<UserSummary>

    @POST("api/v1/auth/login")
    suspend fun login(@Body body: LoginRequest): Response<LoginResponse>

    @POST("api/v1/auth/logout")
    suspend fun logout(): Response<Unit>

    @GET("api/v1/auth/me")
    suspend fun me(): Response<UserSummary>

    @POST("api/v1/auth/refresh")
    suspend fun refresh(): Response<LoginResponse>

    @POST("api/v1/auth/change-password")
    suspend fun changePassword(@Body body: com.rknepp.parity.auth.data.dto.ChangePasswordRequest): Response<Unit>

    @retrofit2.http.PATCH("api/v1/auth/me")
    suspend fun updateProfile(@Body body: com.rknepp.parity.auth.data.dto.UpdateProfileRequest): Response<UserSummary>
}
