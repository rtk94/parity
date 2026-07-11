package com.rknepp.parity.auth.data

import com.rknepp.parity.auth.data.dto.DeleteAccountRequest
import com.rknepp.parity.auth.data.dto.LoginRequest
import com.rknepp.parity.auth.data.dto.LoginResponse
import com.rknepp.parity.auth.data.dto.RegisterRequest
import com.rknepp.parity.home.model.UserSummary
import kotlinx.serialization.json.JsonElement
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HTTP
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

    @GET("api/v1/auth/me/export")
    suspend fun exportData(): Response<JsonElement>

    // DELETE with a request body (the password confirmation); @HTTP is
    // required because Retrofit's @DELETE doesn't accept an @Body.
    @HTTP(method = "DELETE", path = "api/v1/auth/me", hasBody = true)
    suspend fun deleteAccount(@Body body: DeleteAccountRequest): Response<Unit>
}
