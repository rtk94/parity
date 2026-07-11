package com.rknepp.parity

import android.content.Context
import com.rknepp.parity.admin.data.AdminApi
import com.rknepp.parity.admin.data.AdminRepository
import com.rknepp.parity.app.StartupGate
import com.rknepp.parity.auth.data.AuthApi
import com.rknepp.parity.auth.data.AuthRepository
import com.rknepp.parity.auth.events.AuthEventBus
import com.rknepp.parity.home.data.MeRepository
import com.rknepp.parity.ledger.data.LedgerApi
import com.rknepp.parity.ledger.data.LedgerRepository
import com.rknepp.parity.network.AuthInterceptor
import com.rknepp.parity.network.RetrofitFactory
import com.rknepp.parity.network.TokenAuthenticator
import com.rknepp.parity.push.PushRegistrar
import com.rknepp.parity.relationships.data.RelationshipApi
import com.rknepp.parity.relationships.data.RelationshipRepository
import com.rknepp.parity.storage.AndroidKeystoreTinkAeadProvider
import com.rknepp.parity.storage.SecureTokenStore
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

    private val aeadProvider = AndroidKeystoreTinkAeadProvider(
        keysetFile = File(appContext.filesDir, "tink/token_keyset.json"),
    )
    val tokenStore: SecureTokenStore = SecureTokenStore(appContext, aeadProvider)

    val authEventBus: AuthEventBus = AuthEventBus()

    val startupGate: StartupGate = StartupGate(tokenStore)

    private val currentBaseUrl = AtomicReference<String?>(null)
    private val currentRetrofit = AtomicReference<Retrofit?>(null)
    private val currentAuthApi = AtomicReference<AuthApi?>(null)
    private val currentRelationshipApi = AtomicReference<RelationshipApi?>(null)
    private val currentLedgerApi = AtomicReference<LedgerApi?>(null)
    private val currentAdminApi = AtomicReference<AdminApi?>(null)

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

    val relationshipRepository = RelationshipRepository(
        apiProvider = ::requireRelationshipApi,
    )

    val ledgerRepository = LedgerRepository(
        apiProvider = ::requireLedgerApi,
    )

    val adminRepository = AdminRepository(
        apiProvider = ::requireAdminApi,
    )

    val pushRegistrar = PushRegistrar(
        authApiProvider = ::requireAuthApi,
    )

    /** Register a freshly-rotated FCM token (from the messaging service). */
    fun onNewPushToken(token: String) {
        appScope.launch { pushRegistrar.register(token) }
    }

    /** Register this device's push token if there is an active session. */
    fun registerDeviceIfLoggedIn() {
        appScope.launch {
            if (tokenStore.token.firstOrNull() != null) {
                pushRegistrar.registerCurrentDevice()
            }
        }
    }

    init {
        // Build the Retrofit stack immediately with the hardcoded URL
        rebuild(BuildConfig.BASE_URL)
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
        currentRelationshipApi.set(retrofit.create(RelationshipApi::class.java))
        currentLedgerApi.set(retrofit.create(LedgerApi::class.java))
        currentAdminApi.set(retrofit.create(AdminApi::class.java))
    }

    private fun requireAuthApi(): AuthApi = currentAuthApi.get()!!

    private fun requireRelationshipApi(): RelationshipApi = currentRelationshipApi.get()!!

    private fun requireLedgerApi(): LedgerApi = currentLedgerApi.get()!!

    private fun requireAdminApi(): AdminApi = currentAdminApi.get()!!
}
