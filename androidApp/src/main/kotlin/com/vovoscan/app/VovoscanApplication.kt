package com.vovoscan.app

import android.app.Application
import com.vovoscan.feature.auth.domain.AuthRepository
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
            modules(appModules(AppConfig(BuildConfig.API_BASE_URL), androidPlatformModule(this@VovoscanApplication)))
        }
        // Session gardée sur l'appareil, avant le premier écran ; la confirmation réseau suit, sans bloquer hors ligne.
        runBlocking { GlobalContext.get().get<AuthRepository>().restore() }
    }
}
