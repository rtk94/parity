package com.rknepp.parity.push

import com.google.firebase.messaging.FirebaseMessaging
import com.rknepp.parity.auth.data.AuthApi
import com.rknepp.parity.auth.data.dto.RegisterDeviceRequest
import com.rknepp.parity.auth.data.dto.UnregisterDeviceRequest
import com.rknepp.parity.network.apiCall
import com.rknepp.parity.network.apiCallUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Registers and unregisters this device's FCM token with the backend
 * (`/auth/devices`). Everything here is best-effort: push is a
 * convenience, never allowed to disrupt auth flows, so failures are
 * swallowed. Calls require a valid session — the auth interceptor
 * attaches the bearer token — so callers should only invoke these while
 * logged in (a stray call simply 401s and is ignored).
 */
class PushRegistrar(private val authApiProvider: () -> AuthApi) {

    /** Register a specific token (e.g. from `onNewToken`). */
    suspend fun register(token: String) {
        runCatching { apiCall { authApiProvider().registerDevice(RegisterDeviceRequest(token)) } }
    }

    /** Fetch the current FCM token and register it. */
    suspend fun registerCurrentDevice() {
        val token = currentToken() ?: return
        register(token)
    }

    /** Fetch the current FCM token and unregister it (called on logout). */
    suspend fun unregisterCurrentDevice() {
        val token = currentToken() ?: return
        runCatching {
            apiCallUnit { authApiProvider().unregisterDevice(UnregisterDeviceRequest(token)) }
        }
    }

    private suspend fun currentToken(): String? = suspendCancellableCoroutine { cont ->
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token -> if (cont.isActive) cont.resume(token) }
            .addOnFailureListener { if (cont.isActive) cont.resume(null) }
    }
}
