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
