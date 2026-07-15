package com.rknepp.parity.auth.data.dto

import com.rknepp.parity.home.model.UserSummary
import kotlinx.serialization.Serializable

@Serializable
data class RegisterRequest(
    val username: String,
    val password: String,
    val display_name: String,
    // Optional recovery email. Null is omitted from the JSON (the
    // converter uses explicitNulls = false), so the backend treats an
    // absent value as "no email".
    val email: String? = null,
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
    // Always sent so the caller can both set and clear the recovery
    // email: a non-blank value sets it, an empty string clears it
    // server-side (PATCH /auth/me keys off the field being present).
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
    val token: String,
    val new_password: String,
)
