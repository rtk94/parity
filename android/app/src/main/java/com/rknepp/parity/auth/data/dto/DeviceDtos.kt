package com.rknepp.parity.auth.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class RegisterDeviceRequest(
    val token: String,
    val platform: String = "android",
)

@Serializable
data class UnregisterDeviceRequest(
    val token: String,
)
