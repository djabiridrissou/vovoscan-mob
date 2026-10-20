package com.vovoscan.shared

import android.content.Context
import com.vovoscan.core.database.DatabaseDriverFactory
import com.vovoscan.core.security.AndroidSecureStore
import com.vovoscan.core.security.SecureStore
import org.koin.core.module.Module
import org.koin.dsl.module

fun androidPlatformModule(context: Context): Module =
    module {
        single { DatabaseDriverFactory(context) }
        single<SecureStore> { AndroidSecureStore(context) }
    }
