package com.vovoscan.app.ui.common

import android.content.Context
import coil3.ImageLoader
import coil3.intercept.Interceptor
import coil3.network.httpHeaders
import coil3.request.ImageResult
import com.vovoscan.shared.imageRefererFor

/** The app's image loader: Coil's defaults, plus the Referer some sources ask for with their page images. */
fun vovoscanImageLoader(context: Context): ImageLoader = ImageLoader.Builder(context).components { add(SourceReferer) }.build()

/**
 * Adds `Referer` to image requests going to the hosts of a source that declares one (`content.comic.referer`,
 * `ImageReferers`); every other request (the vovoscan API's signed URLs, files on the device) is left as it is.
 */
private object SourceReferer : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val referer = (request.data as? String)?.let(::imageRefererFor) ?: return chain.proceed()
        val headers =
            request.httpHeaders
                .newBuilder()
                .set("Referer", referer)
                .build()
        return chain.withRequest(request.newBuilder().httpHeaders(headers).build()).proceed()
    }
}
