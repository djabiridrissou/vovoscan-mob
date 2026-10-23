package com.vovoscan.app.downloads

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vovoscan.app.MainActivity
import com.vovoscan.app.R
import com.vovoscan.core.common.i18n.I18n
import com.vovoscan.feature.reader.domain.DownloadActivity
import com.vovoscan.feature.reader.domain.DownloadScheduler
import com.vovoscan.feature.reader.domain.DownloadsRepository
import com.vovoscan.feature.reader.presentation.DownloadNotice
import com.vovoscan.feature.reader.presentation.DownloadText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Android side of background downloads. The shared queue (`DownloadsRepository`) downloads; this only keeps it going
 * while the app is in the background or after it was killed, within Android's rules:
 * - [WorkManagerDownloadScheduler]: one unique work, constraint "connected" (or "unmetered" with Wi-Fi only),
 *   expedited when the quota allows, persisted by WorkManager across kills and reboots.
 * - [DownloadWorker]: runs `drain()` as a foreground service of type `dataSync` with a progress notification
 *   ("Downloading 2 chapters…", Cancel). Where Android refuses a foreground service (started from the background on
 *   Android 12+), it carries on as plain work, which the system may stop after ~10 minutes and run again later.
 */
private const val WORK_NAME = "downloads"
private const val CHANNEL_ID = "downloads"
private const val NOTIFICATION_ID = 4107
private const val TAG = "VovoscanDownloads"

/** Schedules the worker. Off the caller's thread: WorkManager's state is read (blocking) before enqueueing. */
class WorkManagerDownloadScheduler(
    context: Context,
) : DownloadScheduler {
    private val context = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()

    override fun schedule(
        wifiOnly: Boolean,
        replace: Boolean,
    ) {
        executor.execute {
            val work = WorkManager.getInstance(context)
            // Already waiting for its turn (or its network): nothing to add. A running worker that is about to finish
            // gets a successor, so work queued at that moment isn't lost.
            if (!replace) {
                val infos = runCatching { work.getWorkInfosForUniqueWork(WORK_NAME).get() }.getOrDefault(emptyList())
                if (infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }) return@execute
            }
            val request =
                OneTimeWorkRequestBuilder<DownloadWorker>()
                    .setConstraints(
                        Constraints
                            .Builder()
                            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                            .build(),
                    ).setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                    .addTag(WORK_NAME)
                    .build()
            work.enqueueUniqueWork(WORK_NAME, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }

    override fun cancel() {
        executor.execute { WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME) }
    }
}

/**
 * Drives the shared queue until it has nothing it can do: success once empty, retry when it waits for a network the
 * constraint didn't foresee. Stopped by the system while the app is in the background (network lost, time limit),
 * it pauses the queue cleanly (the chapter goes back to the queue) and WorkManager runs it again later.
 */
class DownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params),
    KoinComponent {
    private val downloads: DownloadsRepository by inject()

    override suspend fun getForegroundInfo(): ForegroundInfo =
        DownloadNotifications.foregroundInfo(applicationContext, DownloadText.notification(downloads.observeActivity().first()))

    override suspend fun doWork(): Result =
        coroutineScope {
            goForeground(downloads.observeActivity().first())
            val progress = launch { report() }
            try {
                val block = downloads.drain()
                Log.i(TAG, "worker done, waiting for: $block")
                if (block == null) Result.success() else Result.retry()
            } catch (e: CancellationException) {
                Log.i(TAG, "worker stopped (${stopReasonOrNull()}), app visible: ${AppVisibility.visible}")
                // In the background the app may be frozen next: stop the requests now rather than let them time out.
                if (!AppVisibility.visible) withContext(NonCancellable) { downloads.pause() }
                throw e
            } finally {
                // An update posted after WorkManager took its foreground notification down would stay on screen.
                withContext(NonCancellable) {
                    progress.cancelAndJoin()
                    DownloadNotifications.clear(applicationContext)
                }
            }
        }

    private fun stopReasonOrNull(): Int? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) stopReason else null

    /** A foreground service keeps the process alive for long downloads; refused from the background on Android 12+. */
    private suspend fun goForeground(activity: DownloadActivity) {
        try {
            setForeground(DownloadNotifications.foregroundInfo(applicationContext, DownloadText.notification(activity)))
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException: carry on as plain background work.
            Log.i(TAG, "no foreground service: ${e.message}")
        }
    }

    /** The notification follows the queue, at most twice a second. */
    private suspend fun report() {
        downloads.observeActivity().conflate().collect { activity ->
            if (activity.busy) DownloadNotifications.update(applicationContext, DownloadText.notification(activity))
            delay(500)
        }
    }
}

/** The notification's Cancel: forgets everything not downloaded yet (what is on the device stays). */
class CancelDownloadsReceiver :
    BroadcastReceiver(),
    KoinComponent {
    private val downloads: DownloadsRepository by inject()
    private val scope: CoroutineScope by inject()

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val pending = goAsync()
        scope.launch {
            try {
                downloads.cancelAll()
            } finally {
                pending.finish()
            }
        }
    }
}

object DownloadNotifications {
    fun foregroundInfo(
        context: Context,
        notice: DownloadNotice,
    ): ForegroundInfo {
        val notification = build(context, notice)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    fun clear(context: Context) = NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)

    /** Without the notification permission (Android 13+) nothing shows, and the downloads go on all the same. */
    fun update(
        context: Context,
        notice: DownloadNotice,
    ) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        try {
            manager.notify(NOTIFICATION_ID, build(context, notice))
        } catch (e: SecurityException) {
            Log.i(TAG, "notification refused: ${e.message}")
        }
    }

    private fun build(
        context: Context,
        notice: DownloadNotice,
    ): android.app.Notification {
        ensureChannel(context)
        val open =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val cancel =
            PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, CancelDownloadsReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val progress = notice.progress
        return NotificationCompat
            .Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(notice.title)
            .setContentText(notice.text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setProgress(100, ((progress ?: 0f) * 100).toInt(), progress == null)
            .setContentIntent(open)
            .addAction(0, I18n.text("common.cancel"), cancel)
            .build()
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, I18n.text("downloads.title"), NotificationManager.IMPORTANCE_LOW).apply {
                description = I18n.text("downloads.channelDetail")
                setShowBadge(false)
            },
        )
    }
}

/** Whether one of the app's activities is started (on screen), for the worker's stop handling. */
object AppVisibility : Application.ActivityLifecycleCallbacks {
    @Volatile
    private var started = 0

    val visible: Boolean get() = started > 0

    fun register(application: Application) = application.registerActivityLifecycleCallbacks(this)

    override fun onActivityStarted(activity: Activity) {
        started++
    }

    override fun onActivityStopped(activity: Activity) {
        started = (started - 1).coerceAtLeast(0)
    }

    override fun onActivityCreated(
        activity: Activity,
        savedInstanceState: Bundle?,
    ) = Unit

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(
        activity: Activity,
        outState: Bundle,
    ) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}
