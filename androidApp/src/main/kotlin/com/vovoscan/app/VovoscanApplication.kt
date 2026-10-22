package com.vovoscan.app

import android.app.Application
import com.vovoscan.feature.auth.domain.AuthRepository
import com.vovoscan.feature.reader.domain.DownloadsRepository
import com.vovoscan.shared.AppConfig
import com.vovoscan.shared.androidPlatformModule
import com.vovoscan.shared.appModules
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin

class VovoscanApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@VovoscanApplication)
            val config = AppConfig(BuildConfig.API_BASE_URL, allowLocalPlugins = BuildConfig.DEBUG)
            modules(appModules(config, androidPlatformModule(this@VovoscanApplication)))
        }
        // Session stored on the device, before the first screen; the network check follows, without blocking offline.
        runBlocking { GlobalContext.get().get<AuthRepository>().restore() }
        // Chapters left downloading when the app stopped carry on (while the app runs: no background work).
        GlobalContext.get().get<DownloadsRepository>().resume()
    }
}
