package com.rknepp.parity.network

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ApiResultTest {

    @Test
    fun successResponseMapsToSuccess() = runTest {
        val result = apiCall {
            retrofit2.Response.success(Echo("ok"))
        }
        assertTrue(result is ApiResult.Success)
        assertEquals("ok", (result as ApiResult.Success).data.value)
    }

    @Test
    fun httpErrorWithEnvelopeMapsToHttpFailureWithParsedError() = runTest {
        val body = """{"error":{"code":"unauthorized","message":"nope"}}"""
        val result = apiCall<Echo> {
            retrofit2.Response.error(
                401,
                body.toResponseBody("application/json".toMediaType()),
            )
        }
        assertTrue(result is ApiResult.HttpFailure)
        val fail = result as ApiResult.HttpFailure
        assertEquals(401, fail.code)
        assertNotNull(fail.error)
        assertEquals("unauthorized", fail.error?.code)
    }

    @Test
    fun httpErrorWithNonEnvelopeBodyMapsToHttpFailureWithNullError() = runTest {
        val body = """{"unrelated":"payload"}"""
        val result = apiCall<Echo> {
            retrofit2.Response.error(
                500,
                body.toResponseBody("application/json".toMediaType()),
            )
        }
        assertTrue(result is ApiResult.HttpFailure)
        val fail = result as ApiResult.HttpFailure
        assertEquals(500, fail.code)
        assertNull(fail.error)
    }

    @Test
    fun ioExceptionMapsToNetworkFailure() = runTest {
        val result = apiCall<Echo> {
            throw IOException("boom")
        }
        assertTrue(result is ApiResult.NetworkFailure)
    }

    @Test
    fun unexpectedThrowableMapsToUnexpectedFailure() = runTest {
        val result = apiCall<Echo> {
            throw IllegalStateException("uh oh")
        }
        assertTrue(result is ApiResult.UnexpectedFailure)
    }

    @Test
    fun emptyBodyWithoutSuccessFallbackIsUnexpected() = runTest {
        val rawRequest = Request.Builder().url("https://example.test/").build()
        val rawResponse = Response.Builder()
            .request(rawRequest)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("".toResponseBody("application/json".toMediaType()))
            .build()
        val result = apiCall<Echo> {
            retrofit2.Response.success(null, rawResponse)
        }
        assertTrue(result is ApiResult.UnexpectedFailure)
    }

    @kotlinx.serialization.Serializable
    data class Echo(val value: String)
}
