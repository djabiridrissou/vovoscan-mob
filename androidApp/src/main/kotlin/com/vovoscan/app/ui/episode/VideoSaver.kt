package com.vovoscan.app.ui.episode

import android.app.DownloadManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.core.net.toUri

/**
 * Saves an episode's MP4 with the system's DownloadManager: the signed link needs no session, the download survives
 * the app, and its notification opens the video. Android 10+: the shared Movies/vovoscan folder (no permission);
 * Android 8-9: the app's own Movies/vovoscan folder (writing the shared one would need the storage permission).
 */
object VideoSaver {
    private const val FOLDER = "vovoscan"

    fun save(
        context: Context,
        url: String,
        fileName: String,
    ): Boolean =
        runCatching {
            val request =
                DownloadManager
                    .Request(url.toUri())
                    .setTitle(fileName)
                    .setMimeType("video/mp4")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_MOVIES, "$FOLDER/$fileName")
            } else {
                request.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_MOVIES, "$FOLDER/$fileName")
            }
            context.getSystemService(DownloadManager::class.java).enqueue(request)
            Toast.makeText(context, "Downloading to Movies/vovoscan.", Toast.LENGTH_SHORT).show()
        }.isSuccess
}
