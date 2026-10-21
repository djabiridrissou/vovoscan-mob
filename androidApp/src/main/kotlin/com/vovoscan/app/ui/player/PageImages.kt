package com.vovoscan.app.ui.player

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The page images of the player, as bitmaps ready to draw: the page itself (at most [MAX_SIDE] px, hardware bitmap)
 * and its backdrop, a tiny copy blurred once per page (box blur on [BLUR_WIDTH] px, then drawn scaled up with
 * bilinear filtering: a soft, wide blur that costs nothing per frame). Loaded through Coil, so pages already seen in
 * the reader come from its memory and disk caches. Signed URLs change when they are refreshed, not their content:
 * the cache key is the URL without its query. A few pages are kept (the shot, the fading one, the next).
 */
class PageImages(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onFailed: (url: String) -> Unit,
) {
    class Page(
        val image: ImageBitmap,
        val backdrop: ImageBitmap,
    )

    /** Snapshot state: the Canvas redraws when a page arrives. */
    private val pages = mutableStateMapOf<String, Page>()
    private val loading = HashSet<String>()
    private val order = ArrayDeque<String>()

    /** The page if it is loaded (read while drawing). */
    fun peek(url: String?): Page? = url?.let { pages[keyOf(it)] }

    /** Loads the page unless it is loaded or on its way (call from the frame loop, or to warm the next page). */
    fun request(url: String?) {
        if (url == null) return
        val key = keyOf(url)
        if (pages.containsKey(key)) {
            order.remove(key)
            order.addLast(key)
            return
        }
        if (!loading.add(key)) return
        scope.launch {
            val page = load(url, key)
            loading.remove(key)
            if (page == null) {
                onFailed(url)
                return@launch
            }
            pages[key] = page
            order.remove(key)
            order.addLast(key)
            while (order.size > KEEP) pages.remove(order.removeFirst())
        }
    }

    private suspend fun load(
        url: String,
        key: String,
    ): Page? {
        val loader = SingletonImageLoader.get(context)
        val full =
            loader.execute(
                ImageRequest
                    .Builder(context)
                    .data(url)
                    .memoryCacheKey(key)
                    .diskCacheKey(key)
                    .size(MAX_SIDE)
                    .build(),
            )
        if (full !is SuccessResult) return null
        val small =
            loader.execute(
                ImageRequest
                    .Builder(context)
                    .data(url)
                    .memoryCacheKey("$key#backdrop")
                    .diskCacheKey(key)
                    .size(BLUR_WIDTH * 2)
                    .allowHardware(false)
                    .build(),
            ) as? SuccessResult ?: return null
        val backdrop = withContext(Dispatchers.Default) { blurred(small.image.toBitmap()) }
        return Page(full.image.toBitmap().asImageBitmap(), backdrop.asImageBitmap())
    }

    private fun keyOf(url: String) = "page:" + url.substringBefore('?')

    private companion object {
        const val KEEP = 4
        const val MAX_SIDE = 2400
        const val BLUR_WIDTH = 72
        const val BLUR_RADIUS = 2
        const val BLUR_PASSES = 3

        /** Downscale to [BLUR_WIDTH] px wide, then three box-blur passes (≈ a Gaussian). */
        fun blurred(source: Bitmap): Bitmap {
            val w = BLUR_WIDTH
            val h = maxOf(1, (source.height.toLong() * w / maxOf(1, source.width)).toInt())
            val argb = if (source.config == Bitmap.Config.ARGB_8888) source else source.copy(Bitmap.Config.ARGB_8888, false)
            val small = Bitmap.createScaledBitmap(argb, w, h, true)
            val pixels = IntArray(w * h)
            small.getPixels(pixels, 0, w, 0, 0, w, h)
            val scratch = IntArray(w * h)
            repeat(BLUR_PASSES) {
                boxBlur(pixels, scratch, w, h, horizontal = true)
                boxBlur(scratch, pixels, w, h, horizontal = false)
            }
            return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        }

        private fun boxBlur(
            src: IntArray,
            dst: IntArray,
            w: Int,
            h: Int,
            horizontal: Boolean,
        ) {
            val lines = if (horizontal) h else w
            val length = if (horizontal) w else h
            val span = 2 * BLUR_RADIUS + 1
            for (line in 0 until lines) {
                var r = 0
                var g = 0
                var b = 0

                fun at(i: Int): Int {
                    val c = i.coerceIn(0, length - 1)
                    return if (horizontal) src[line * w + c] else src[c * w + line]
                }
                for (i in -BLUR_RADIUS..BLUR_RADIUS) {
                    val p = at(i)
                    r += (p shr 16) and 0xFF
                    g += (p shr 8) and 0xFF
                    b += p and 0xFF
                }
                for (i in 0 until length) {
                    val out = (0xFF shl 24) or ((r / span) shl 16) or ((g / span) shl 8) or (b / span)
                    if (horizontal) dst[line * w + i] = out else dst[i * w + line] = out
                    val add = at(i + BLUR_RADIUS + 1)
                    val drop = at(i - BLUR_RADIUS)
                    r += ((add shr 16) and 0xFF) - ((drop shr 16) and 0xFF)
                    g += ((add shr 8) and 0xFF) - ((drop shr 8) and 0xFF)
                    b += (add and 0xFF) - (drop and 0xFF)
                }
            }
        }
    }
}
