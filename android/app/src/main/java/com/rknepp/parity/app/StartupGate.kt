package com.rknepp.parity.app

import com.rknepp.parity.storage.SecureTokenStore
import com.rknepp.parity.storage.ServerUrlStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

enum class StartupDestination { Connect, Login, Home }

class StartupGate(
    private val serverUrlStore: ServerUrlStore,
    private val tokenStore: SecureTokenStore,
) {
    val initialDestination: Flow<StartupDestination> =
        combine(serverUrlStore.serverUrl, tokenStore.token) { url, token ->
            when {
                url.isNullOrBlank() -> StartupDestination.Connect
                token.isNullOrBlank() -> StartupDestination.Login
                else -> StartupDestination.Home
            }
        }.distinctUntilChanged()
}
