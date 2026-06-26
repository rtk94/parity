package com.rknepp.parity.app

import com.rknepp.parity.storage.SecureTokenStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

enum class StartupDestination { Login, Home }

class StartupGate(
    private val tokenStore: SecureTokenStore,
) {
    val initialDestination: Flow<StartupDestination> =
        tokenStore.token.map { token ->
            if (token.isNullOrBlank()) {
                StartupDestination.Login
            } else {
                StartupDestination.Home
            }
        }.distinctUntilChanged()
}
