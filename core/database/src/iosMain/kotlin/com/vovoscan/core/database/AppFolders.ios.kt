package com.vovoscan.core.database

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask

/**
 * `Library/Application Support/Downloads` in the app's container. Excluded from iCloud backups: pages can be
 * downloaded again. The container's path changes with app updates, so the database keeps paths relative to it.
 */
@OptIn(ExperimentalForeignApi::class)
fun appFolders(): AppFolders {
    val manager = NSFileManager.defaultManager
    val support =
        manager.URLForDirectory(NSApplicationSupportDirectory, NSUserDomainMask, null, true, null)
            ?: error("No Application Support folder")
    val downloads: NSURL = support.URLByAppendingPathComponent("Downloads", isDirectory = true) ?: error("No downloads folder")
    manager.createDirectoryAtURL(downloads, withIntermediateDirectories = true, attributes = null, error = null)
    downloads.setResourceValue(NSNumber(bool = true), forKey = NSURLIsExcludedFromBackupKey, error = null)
    return AppFolders(downloads = downloads.path ?: error("No downloads path"))
}
