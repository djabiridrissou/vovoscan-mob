package com.vovoscan.app.ui.common

import android.app.Activity
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import com.vovoscan.core.common.Adaptive
import com.vovoscan.core.common.Hinge

/** The app's layout for the window it has now (shared rules, `Adaptive`): phones get the defaults. */
val LocalAdaptive = staticCompositionLocalOf { Adaptive.of(width = 400f, height = 800f) }

/**
 * Measures the window's content area and reads a foldable's hinge (Jetpack WindowManager), then provides the layout
 * shared Kotlin decides for them. Unfolding, folding, half opening or resizing a window only changes this value: the
 * activity is not recreated (`configChanges`), so what is on screen stays where it was.
 */
@Composable
fun AdaptiveWindow(content: @Composable () -> Unit) {
    val activity = LocalContext.current as? Activity
    val density = LocalDensity.current
    var fold by remember { mutableStateOf<FoldingFeature?>(null) }
    if (activity != null) {
        LaunchedEffect(activity) {
            WindowInfoTracker
                .getOrCreate(activity)
                .windowLayoutInfo(activity)
                .collect { info -> fold = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull() }
        }
    }
    BoxWithConstraints {
        val width = maxWidth.value
        val height = maxHeight.value
        val hinge =
            fold?.let { feature ->
                // Window coordinates (px) to dp; the content area starts at the window's left edge.
                val bounds = feature.bounds
                val vertical = feature.orientation == FoldingFeature.Orientation.VERTICAL
                with(density) {
                    Hinge(
                        vertical = vertical,
                        start = (if (vertical) bounds.left else bounds.top).toDp().value,
                        end = (if (vertical) bounds.right else bounds.bottom).toDp().value,
                        separating = feature.isSeparating,
                        halfOpened = feature.state == FoldingFeature.State.HALF_OPENED,
                    )
                }
            }
        val layout = remember(width, height, hinge) { Adaptive.of(width, height, hinge) }
        CompositionLocalProvider(LocalAdaptive provides layout) { content() }
    }
}
