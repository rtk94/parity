package com.rknepp.parity

import android.content.Context
import com.rknepp.parity.app.StartupGate
import com.rknepp.parity.auth.data.AuthApi
import com.rknepp.parity.auth.data.AuthRepository
import com.rknepp.parity.auth.events.AuthEventBus
import com.rknepp.parity.home.data.MeRepository
import com.rknepp.parity.network.AuthInterceptor
import com.rknepp.parity.network.RetrofitFactory
import com.rknepp.parity.network.TokenAuthenticator
import com.rknepp.parity.storage.AndroidKeystoreTinkAeadProvider
import com.rknepp.parity.storage.SecureTokenStore
import com.rknepp.parity.storage.ServerUrlStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Application-scoped service locator. Constructed once in
 * [ParityApplication.onCreate]; never recreated.
 *
 * Holds the current [Retrofit] internally and rebuilds it whenever the
 * persisted server URL changes. API interfaces are accessed via the
 * `*Provider` factories below so they always see the active client.
 */
class ServiceLocator(context: Context) {

    private val appContext = context.applicationContext
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val serverUrlStore: ServerUrlStore = ServerUrlStore(appContext)

    private val aeadProvider = AndroidKeystoreTinkAeadProvider(
        keysetFile = File(appContext.filesDir, "tink/token_keyset.json"),
    )
    val tokenStore: SecureTokenStore = SecureTokenStore(appContext, aeadProvider)

    val authEventBus: AuthEventBus = AuthEventBus()

    val startupGate: StartupGate = StartupGate(serverUrlStore, tokenStore)

    private val currentBaseUrl = AtomicReference<String?>(null)
    private val currentRetrofit = AtomicReference<Retrofit?>(null)
    private val currentAuthApi = AtomicReference<AuthApi?>(null)

    private val authInterceptor = AuthInterceptor(tokenStore)

    private val refreshClient: OkHttpClient by lazy {
        // Separate client for refresh-call requests inside the
        // authenticator — no auth interceptor, no authenticator,
        // to avoid any chance of recursion.
        RetrofitFactory.bareClient()
    }

    private val tokenAuthenticator = TokenAuthenticator(
        baseUrlProvider = { currentBaseUrl.get() },
        refreshClientProvider = { refreshClient },
        tokenStore = tokenStore,
        authEventBus = authEventBus,
    )

    val authRepository = AuthRepository(
        authApiProvider = ::requireAuthApi,
        tokenStore = tokenStore,
    )

    val meRepository = MeRepository(
        authApiProvider = ::requireAuthApi,
    )

    init {
        // Seed the initial Retrofit synchronously so the first request
        // does not race the URL observer.
        val initial = runBlocking { serverUrlStore.serverUrl.firstOrNull() }
        if (!initial.isNullOrBlank()) rebuild(initial)

        appScope.launch {
            serverUrlStore.serverUrl.distinctUntilChanged().collect { url ->
                if (url.isNullOrBlank()) {
                    currentBaseUrl.set(null)
                    currentRetrofit.set(null)
                    currentAuthApi.set(null)
                } else if (url != currentBaseUrl.get()) {
                    rebuild(url)
                }
            }
        }
    }

    private fun rebuild(baseUrl: String) {
        val retrofit = RetrofitFactory.create(
            baseUrl = baseUrl,
            authInterceptor = authInterceptor,
            authenticator = tokenAuthenticator,
        )
        currentBaseUrl.set(baseUrl)
        currentRetrofit.set(retrofit)
        currentAuthApi.set(retrofit.create(AuthApi::class.java))
    }

    private fun requireAuthApi(): AuthApi = currentAuthApi.get()
        ?: error("Server URL not configured. Call ServerUrlStore.set(url) first.")
}
