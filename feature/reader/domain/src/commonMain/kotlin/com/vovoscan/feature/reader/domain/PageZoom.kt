package com.vovoscan.feature.reader.domain

/**
 * How the reading area is zoomed: drawn as `screen = content × scale + (x, y)`, origin at the area's top left, in
 * the native side's units (pixels on Android, points on iOS). Zoomed content always covers the area: `x` lies in
 * `width × (1 − scale) .. 0`, `y` likewise.
 */
data class ZoomTransform(
    val scale: Float = 1f,
    val x: Float = 0f,
    val y: Float = 0f,
) {
    val zoomed: Boolean get() = scale > PageZoom.ZOOMED
}

/** A pan while zoomed: the new transform, and what the area could not take as a scroll of the content beneath. */
data class ZoomPan(
    val transform: ZoomTransform,
    /** Content units (screen distance / scale); positive scrolls forward (the finger went up past the bottom). */
    val scroll: Float,
)

/**
 * Pinch and double-tap zoom of a comic: the page on screen (paged modes) or the whole strip (long strips, as Mihon
 * zooms its webtoon view: pages stay joined, the strip keeps scrolling under the zoom). Pure: the native readers
 * track the fingers and draw, these rules say where the zoom goes.
 */
object PageZoom {
    const val MIN = 1f
    const val MAX = 4f

    /** Double tap (when the setting is on): from fit to this scale around the tapped point, and back. */
    const val DOUBLE_TAP = 2f

    /** Up to this scale the area counts as unzoomed (a pinch let go just above 1×). */
    const val ZOOMED = 1.02f

    val None = ZoomTransform()

    /** Within bounds: scale in [MIN, MAX] (1× has no offset), the content covering the area. */
    fun clamp(
        transform: ZoomTransform,
        width: Float,
        height: Float,
    ): ZoomTransform {
        val scale = transform.scale.coerceIn(MIN, MAX)
        if (scale <= ZOOMED) return None
        if (width <= 0f || height <= 0f) return transform.copy(scale = scale)
        return ZoomTransform(
            scale,
            transform.x.coerceIn(width * (1 - scale), 0f),
            transform.y.coerceIn(height * (1 - scale), 0f),
        )
    }

    /**
     * A pinch step: scale by [factor] around the fingers' centre ([focusX], [focusY]: the content under it stays under
     * it), and follow that centre as it moves by ([panX], [panY]).
     */
    fun pinch(
        transform: ZoomTransform,
        factor: Float,
        focusX: Float,
        focusY: Float,
        panX: Float,
        panY: Float,
        width: Float,
        height: Float,
    ): ZoomTransform {
        if (factor <= 0f) return transform
        val scale = (transform.scale * factor).coerceIn(MIN, MAX)
        val applied = scale / transform.scale
        val x = focusX - (focusX - transform.x) * applied + panX
        val y = focusY - (focusY - transform.y) * applied + panY
        return clamp(ZoomTransform(scale, x, y), width, height)
    }

    /**
     * A one-finger pan while zoomed. Across, the zoomed view moves within its bounds. Down the area, the view moves
     * first, and what goes past its edge scrolls the content beneath ([ZoomPan.scroll]): a strip, or a tall page
     * inside its page. Nothing moves at 1× (the scroll view has the finger then).
     */
    fun pan(
        transform: ZoomTransform,
        dx: Float,
        dy: Float,
        width: Float,
        height: Float,
    ): ZoomPan {
        if (!transform.zoomed) return ZoomPan(transform, 0f)
        val wanted = transform.y + dy
        val y = wanted.coerceIn(height * (1 - transform.scale), 0f)
        val x = (transform.x + dx).coerceIn(width * (1 - transform.scale), 0f)
        // The finger going up (dy < 0) past the view's bottom moves the content forward.
        val overflow = wanted - y
        return ZoomPan(ZoomTransform(transform.scale, x, y), if (overflow == 0f) 0f else -overflow / transform.scale)
    }

    /** Double tap: fit → [DOUBLE_TAP] around the tapped point (it stays under the finger); zoomed (any scale) → fit. */
    fun doubleTap(
        transform: ZoomTransform,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
    ): ZoomTransform =
        if (transform.zoomed) {
            None
        } else {
            pinch(None, DOUBLE_TAP, x, y, 0f, 0f, width, height)
        }
}
