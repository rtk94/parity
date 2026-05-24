package com.rknepp.parity.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ApiError(
    val code: String,
    val message: String,
    val details: JsonObject? = null,
)

@Serializable
data class ApiErrorEnvelope(val error: ApiError)
