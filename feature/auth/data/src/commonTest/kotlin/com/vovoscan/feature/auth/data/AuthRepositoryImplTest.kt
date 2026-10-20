package com.vovoscan.feature.auth.data

import com.vovoscan.core.common.AppResult
import com.vovoscan.core.security.SecureKeys
import com.vovoscan.core.testing.MemorySecureStore
import com.vovoscan.core.testing.respondJson
import com.vovoscan.core.testing.testHttpClient
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthRepositoryImplTest {
    private val user =
        """{"id":"u1","email":"r@v.app","displayName":"Reader","roleName":"Reader","permissions":["library-entries:update"]}"""

    @Test
    fun signInKeepsTheTokenInTheSecureStore() =
        runTest {
            val store = MemorySecureStore()
            val repo =
                AuthRepositoryImpl(
                    KtorAuthRemoteDataSource(testHttpClient { respondJson("""{"data":{"token":"t1","user":$user}}""") }),
                    SecureSessionLocalDataSource(store),
                )
            val result = repo.signIn("r@v.app", "a-long-password")
            assertTrue(result is AppResult.Ok)
            assertEquals("t1", store.values[SecureKeys.SESSION_TOKEN])
            assertTrue(repo.session.value!!.can("library-entries:update"))
        }

    @Test
    fun offlineTheLocalSessionStaysOpen() =
        runTest {
            val store = MemorySecureStore()
            SecureSessionLocalDataSource(store).save(
                SessionDto("t1", UserDto("u1", "r@v.app", "Reader")),
            )
            val repo =
                AuthRepositoryImpl(
                    KtorAuthRemoteDataSource(testHttpClient { throw IllegalStateException("offline") }),
                    SecureSessionLocalDataSource(store),
                )
            repo.restore()
            assertEquals("u1", repo.session.value?.userId)
        }

    @Test
    fun revokedSessionErasesEverything() =
        runTest {
            val store = MemorySecureStore()
            SecureSessionLocalDataSource(store).save(SessionDto("t1", UserDto("u1", "r@v.app", "Reader")))
            var cleared = false
            val repo =
                AuthRepositoryImpl(
                    KtorAuthRemoteDataSource(testHttpClient { respondJson("{}", HttpStatusCode.Unauthorized) }),
                    SecureSessionLocalDataSource(store),
                ) { cleared = true }
            repo.restore()
            assertNull(repo.session.value)
            assertTrue(store.values.isEmpty())
            assertTrue(cleared)
        }
}
