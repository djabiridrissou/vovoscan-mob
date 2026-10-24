package com.vovoscan.shared

import android.content.Context
import com.vovoscan.core.common.NetworkMonitor
import com.vovoscan.core.common.i18n.DevicePreferences
import com.vovoscan.core.database.AppFolders
import com.vovoscan.core.database.DatabaseDriverFactory
import com.vovoscan.core.database.appFolders
import com.vovoscan.core.network.AndroidNetworkMonitor
import com.vovoscan.core.security.AndroidSecureStore
import com.vovoscan.core.security.SecureStore
import com.vovoscan.feature.together.domain.Device
import com.vovoscan.feature.together.domain.ThisDevice
import org.koin.core.module.Module
import org.koin.dsl.module

fun androidPlatformModule(context: Context): Module =
    module {
        single { DatabaseDriverFactory(context) }
        single<SecureStore> { AndroidSecureStore(context) }
        single<NetworkMonitor> { AndroidNetworkMonitor(context) }
        single { ThisDevice(Device.Android) }
        single<AppFolders> { appFolders(context) }
        single<DevicePreferences> { AndroidDevicePreferences(context) }
    }
