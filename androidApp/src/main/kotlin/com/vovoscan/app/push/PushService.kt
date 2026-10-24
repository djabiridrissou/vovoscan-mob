package com.vovoscan.app.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.vovoscan.app.BuildConfig
import com.vovoscan.app.MainActivity
import com.vovoscan.app.R
import com.vovoscan.core.common.i18n.I18n
import com.vovoscan.feature.notifications.domain.PushDevice
import com.vovoscan.feature.notifications.presentation.NotificationText
import com.vovoscan.feature.notifications.presentation.NotificationsIntent
import com.vovoscan.feature.notifications.presentation.NotificationsViewModel
import com.vovoscan.feature.notifications.presentation.PushRegistrar
import org.json.JSONArray
import org.json.JSONObject
import org.koin.core.context.GlobalContext

/**
 * Firebase on this build, when it was built with a Firebase app (`-Pvovoscan.firebase.*`, or a local
 * `androidApp/google-services.json`; neither is in the repository). Without one nothing starts, no token exists and
 * notifications stay in the app. Set up by hand (no Google Services plugin, the manifest turns automatic start off), so
 * nothing reaches Google before this runs.
 */
object PushSetup {
    val configured: Boolean get() = BuildConfig.FIREBASE_APP_ID.isNotEmpty()

    fun start(context: Context) {
        val registrar = GlobalContext.get().get<PushRegistrar>()
        registrar.start()
        if (!configured) return
        val app =
            runCatching {
                FirebaseApp.getApps(context).firstOrNull() ?: FirebaseApp.initializeApp(
                    context,
                    FirebaseOptions
                        .Builder()
                        .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                        .setApiKey(BuildConfig.FIREBASE_API_KEY)
                        .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                        .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                        .build(),
                )
            }.getOrNull() ?: return
        runCatching {
            val messaging = app.get(FirebaseMessaging::class.java)
            messaging.isAutoInitEnabled = true
            messaging.token.addOnSuccessListener { registrar.tokenChanged(it) }
        }
    }
}

/**
 * Receives sealed pushes (data only, high priority): opens them with this device's key and shows them in the app's
 * language, with the inbox's own sentences (`NotificationText`). A push that does not open is dropped.
 */
class PushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        GlobalContext.getOrNull()?.get<PushRegistrar>()?.tokenChanged(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val koin = GlobalContext.getOrNull() ?: return
        val device = koin.getOrNull<PushDevice>() as? AndroidPushDevice ?: return
        val (key, private) = device.secret() ?: return
        val json = PushCrypto.open(message.data, key.keyId, key.deviceId, private) ?: return
        val body = runCatching { JSONObject(json) }.getOrNull() ?: return
        val kind = body.optString("kind")
        val data = flatten(body.optJSONObject("data") ?: JSONObject())
        val phrased = NotificationText.of(kind, data)
        val target = NotificationText.target(data)
        // The inbox (the bell) moves at once if the app is open.
        koin.getOrNull<NotificationsViewModel>()?.onIntent(NotificationsIntent.Refresh)
        show(body.optString("id").ifEmpty { json.hashCode().toString() }, phrased.title, phrased.body, target.link)
    }

    private fun show(
        id: String,
        title: String,
        text: String,
        link: String?,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        channel(this)
        val open =
            Intent(this, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .apply { link?.let { data = Uri.parse(it) } }
        val pending =
            PendingIntent.getActivity(
                this,
                id.hashCode(),
                open,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notification =
            NotificationCompat
                .Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setColor(0xFFFF2D55.toInt())
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build()
        runCatching { NotificationManagerCompat.from(this).notify(id, 0, notification) }
    }

    companion object {
        const val CHANNEL_ID = "groups"

        fun channel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, I18n.text("notifications.title"), NotificationManager.IMPORTANCE_HIGH),
            )
        }

        /** `data` as plain texts, as the inbox reads it: lists joined (`winners`), objects and nulls left out. */
        fun flatten(data: JSONObject): Map<String, String> =
            data
                .keys()
                .asSequence()
                .mapNotNull { k ->
                    when (val v = data.opt(k)) {
                        null, JSONObject.NULL, is JSONObject -> null
                        is JSONArray ->
                            k to
                                (0 until v.length())
                                    .mapNotNull { i ->
                                        v.opt(i)?.takeUnless { it == JSONObject.NULL }?.toString()
                                    }.joinToString(", ")
                        else -> k to v.toString()
                    }
                }.toMap()
    }
}
