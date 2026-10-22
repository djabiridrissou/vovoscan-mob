package com.vovoscan.core.database

/**
 * Folders the app keeps files in, inside its own storage (never the photo library or a shared folder): erased with
 * the app, and by sign-out for what belongs to the account.
 */
data class AppFolders(
    /** Chapters downloaded for offline reading (absolute path, created on first use). */
    val downloads: String,
)
