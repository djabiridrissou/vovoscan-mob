package com.vovoscan.core.database

import android.content.Context
import java.io.File

/** `files/downloads` in the app's internal storage: private, kept until the app removes it or is uninstalled. */
fun appFolders(context: Context): AppFolders = AppFolders(downloads = File(context.filesDir, "downloads").path)
