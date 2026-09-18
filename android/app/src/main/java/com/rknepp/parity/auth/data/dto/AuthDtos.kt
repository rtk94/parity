package com.rknepp.parity.auth.data.dto

import com.rknepp.parity.home.model.UserSummary
import kotlinx.serialization.Serializable

@Serializable
data class RegisterRequest(
    val username: String,
    val password: String,
    val display_name: String,
    // Recovery email. Required: it is the only self-service way back into
    // an account, so the backend rejects a missing or blank value.
    val email: String,
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
    // The recovery email can be changed but no longer cleared — the
    // backend rejects a blank value, so callers must not send one.
    val email: String,
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
    // The account's email scopes the lookup: reset codes are short, so
    // the backend resolves the account first and only then checks the
    // code against that account.
    val email: String,
    val code: String,
    val new_password: String,
)
