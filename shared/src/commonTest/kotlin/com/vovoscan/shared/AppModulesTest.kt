package com.vovoscan.shared

import com.vovoscan.core.security.SecureStore
import com.vovoscan.core.testing.MemorySecureStore
import com.vovoscan.feature.auth.di.SignOutCleanup
import io.ktor.client.HttpClient
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull

class AppModulesTest {
    @AfterTest
    fun tearDown() = stopKoin()

    @Test
    fun leSocleSeResout() {
        val koin =
            startKoin {
                modules(appModules(AppConfig("https://api.test/"), module { single<SecureStore> { MemorySecureStore() } }))
            }.koin
        assertNotNull(koin.get<HttpClient>())
        assertNotNull(koin.get<SignOutCleanup>())
    }
}
