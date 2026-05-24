package com.rknepp.parity.network

import kotlinx.serialization.json.Json
import retrofit2.Response
import java.io.IOException

sealed interface ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>
    data class HttpFailure(val code: Int, val error: ApiError?) : ApiResult<Nothing>
    data class NetworkFailure(val cause: IOException) : ApiResult<Nothing>
    data class UnexpectedFailure(val cause: Throwable) : ApiResult<Nothing>
}

internal val apiJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

suspend fun <T : Any> apiCall(
    successOnEmpty: T? = null,
    block: suspend () -> Response<T>,
): ApiResult<T> = try {
    val response = block()
    if (response.isSuccessful) {
        val body = response.body()
        when {
            body != null -> ApiResult.Success(body)
            successOnEmpty != null -> ApiResult.Success(successOnEmpty)
            else -> ApiResult.UnexpectedFailure(
                IllegalStateException("Empty response body for ${response.raw().request.url}"),
            )
        }
    } else {
        val raw = response.errorBody()?.string()
        val parsed = raw?.takeIf { it.isNotBlank() }?.let { runCatching {
            apiJson.decodeFromString(ApiErrorEnvelope.serializer(), it).error
        }.getOrNull() }
        ApiResult.HttpFailure(response.code(), parsed)
    }
} catch (io: IOException) {
    ApiResult.NetworkFailure(io)
} catch (t: Throwable) {
    ApiResult.UnexpectedFailure(t)
}

@Suppress("unused")
suspend fun apiCallUnit(block: suspend () -> Response<Unit>): ApiResult<Unit> =
    apiCall(successOnEmpty = Unit, block = block)
