package com.vovoscan.core.sources

import kotlin.concurrent.Volatile

/**
 * The Referer each image host expects, from the sources that declare one (`content.comic.referer`), for the image
 * loaders that know nothing of sources (Coil on Android, the shared URL session behind AsyncImage on iOS): they ask
 * [refererFor] for every image URL. A host maps to the Referer of the first source (in the reader's order) that both
 * allows the host and declares a Referer; any other URL (the vovoscan API, files on the device, a source without a
 * Referer) gets none.
 */
object ImageReferers {
    @Volatile
    private var byHost: Map<String, String> = emptyMap()

    /** Replaces the table with these sources' (rule-based sources in the reader's order). */
    fun update(manifests: List<SourceManifest>) {
        val table = mutableMapOf<String, String>()
        for (manifest in manifests) {
            val referer = manifest.imageReferer ?: continue
            manifest.allowedHosts.forEach { host -> table.getOrPut(host) { referer } }
        }
        byHost = table
    }

    /** The Referer to send with an http(s) image request to [url], or null. */
    fun refererFor(url: String): String? {
        if (!(url.startsWith("https://") || url.startsWith("http://"))) return null
        return hostOf(url)?.let { byHost[it] }
    }
}
