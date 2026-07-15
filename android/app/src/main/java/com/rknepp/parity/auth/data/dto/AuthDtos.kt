package com.rknepp.parity.auth.data.dto

import com.rknepp.parity.home.model.UserSummary
import kotlinx.serialization.Serializable

@Serializable
data class RegisterRequest(
    val username: String,
    val password: String,
    val display_name: String,
)

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
)

@Serializable
data class LoginResponse(
    val token: String,
    val user: UserSummary,
)

@Serializable
data class UpdateProfileRequest(
    val display_name: String,
)

@Serializable
data class ChangePasswordRequest(
    val current_password: String,
    val new_password: String,
)

@Serializable
data class DeleteAccountRequest(
    val password: String,
)

@Serializable
data class PasswordResetRequestBody(
    val email: String,
)

@Serializable
data class PasswordResetConfirmBody(
    val token: String,
    val new_password: String,
)
