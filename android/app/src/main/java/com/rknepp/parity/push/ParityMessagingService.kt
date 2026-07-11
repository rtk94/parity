package com.rknepp.parity.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.rknepp.parity.ParityApplication
import com.rknepp.parity.R

/**
 * Receives FCM callbacks. Token rotations are pushed back to the backend
 * (best-effort); messages delivered while the app is in the foreground
 * are turned into a local notification. Backgrounded messages carrying a
 * `notification` payload are shown by the system directly, and their
 * `data` extras ride the launch intent for deep-linking.
 */
class ParityMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        (application as? ParityApplication)?.serviceLocator?.onNewPushToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val notification = message.notification
        val title = notification?.title ?: message.data["title"] ?: getString(R.string.app_name)
        val body = notification?.body ?: message.data["body"] ?: ""
        PushNotifier.show(this, title, body, message.data)
    }
}
