package com.rknepp.parity.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.rknepp.parity.ParityApplication
import com.rknepp.parity.ServiceLocator
import com.rknepp.parity.push.PushNotifier

class MainActivity : ComponentActivity() {

    // A relationship id pulled off a notification tap, consumed once by
    // the nav host to deep-link. Compose observes it via its `.value`.
    private val pendingDeepLink = mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val locator: ServiceLocator = (application as ParityApplication).serviceLocator

        PushNotifier.ensureChannel(this)
        maybeRequestNotificationPermission()
        // Reconcile this device's push token with the server on launch
        // (covers an already-logged-in start and any token rotation).
        locator.registerDeviceIfLoggedIn()

        pendingDeepLink.value = relationshipIdFrom(intent)

        setContent {
            ParityApp(
                locator = locator,
                deepLinkRelationshipId = pendingDeepLink.value,
                onDeepLinkConsumed = { pendingDeepLink.value = null },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        relationshipIdFrom(intent)?.let { pendingDeepLink.value = it }
    }

    private fun relationshipIdFrom(intent: Intent?): Long? =
        intent?.getStringExtra("relationship_id")?.toLongOrNull()

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST,
            )
        }
    }

    private companion object {
        const val NOTIFICATION_PERMISSION_REQUEST = 1001
    }
}
