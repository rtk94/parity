package com.rknepp.parity.auth.events

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed interface AuthEvent {
    /** Token refresh failed or the session was revoked server-side. */
    data object SessionExpired : AuthEvent

    /** The user deliberately logged out; no "session expired" banner. */
    data object LoggedOut : AuthEvent
}

class AuthEventBus {
    private val _events = MutableSharedFlow<AuthEvent>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<AuthEvent> = _events.asSharedFlow()

    fun tryEmit(event: AuthEvent) {
        _events.tryEmit(event)
    }
}
