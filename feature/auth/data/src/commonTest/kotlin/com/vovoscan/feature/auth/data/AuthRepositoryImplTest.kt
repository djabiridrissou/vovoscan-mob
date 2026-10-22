package com.vovoscan.feature.auth.data

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.feature.auth.domain.Change
import com.vovoscan.feature.auth.domain.ProfileChange
import com.vovoscan.core.security.SecureKeys
import com.vovoscan.core.testing.MemorySecureStore
import com.vovoscan.core.testing.respondJson
import com.vovoscan.core.testing.testHttpClient
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
    fun theUnifiedApiShapeCarriesRoleAndFeatures() =
        runTest {
            val unified =
                """{"id":"u2","email":"o@v.app","name":"Owner","role":"owner","isSuperAdmin":false,""" +
                    """"permissions":["titles:create"],"features":["video"],"emailVerified":true}"""
            val repo =
                AuthRepositoryImpl(
                    KtorAuthRemoteDataSource(testHttpClient { respondJson("""{"data":{"token":"t2","user":$unified}}""") }),
                    SecureSessionLocalDataSource(MemorySecureStore()),
                )
            repo.signIn("o@v.app", "a-long-password")
            val session = repo.session.value!!
            assertEquals("Owner", session.displayName)
            assertEquals(listOf("owner"), session.roles)
            assertTrue(session.has("video"))
            assertTrue(session.isCreator)
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

    private val me =
        """{"id":"u1","email":"r@v.app","displayName":"Reader","locale":"en","region":"FR","timeZone":null,""" +
            """"onboarding":{"completedAt":null,"version":1,"due":true},"deletion":null,"hasPassword":true}"""

    private fun HttpRequestData.text(): String = (body as? TextContent)?.text.orEmpty()

    private suspend fun signedIn(store: MemorySecureStore) =
        SecureSessionLocalDataSource(store).save(SessionDto("t1", ApiJsonUser.decode(me)))

    @Test
    fun theSpecFieldsReachTheSession() =
        runTest {
            val store = MemorySecureStore()
            signedIn(store)
            val repo =
                AuthRepositoryImpl(
                    KtorAuthRemoteDataSource(
                        testHttpClient {
                            respondJson("""{"data":${me.replace("\"deletion\":null", "\"deletion\":{\"scheduledFor\":\"2026-11-21T14:03:00Z\"}")}}""")
                        },
                    ),
                    SecureSessionLocalDataSource(store),
                )
            repo.restore()
            val session = repo.session.value!!
            assertEquals("en", session.locale)
            assertEquals("FR", session.region)
            assertTrue(session.onboardingDue)
            assertEquals(1_795_269_780_000L, session.deletionScheduledAtMillis)
        }

    @Test
    fun aProfileChangeSendsOnlyItsFieldsAndNullClears() =
        runTest {
            val store = MemorySecureStore()
            signedIn(store)
            var sent = ""
            val repo =
                AuthRepositoryImpl(
                    KtorAuthRemoteDataSource(
                        testHttpClient { request ->
                            assertEquals(HttpMethod.Patch, request.method)
                            sent = request.text()
                            respondJson("""{"data":${me.replace("\"locale\":\"en\"", "\"locale\":\"fr\"").replace("\"region\":\"FR\"", "\"region\":null")}}""")
                        },
                    ),
                    SecureSessionLocalDataSource(store),
                )
            val result = repo.updateProfile(ProfileChange(locale = "fr", region = Change.Set(null)))
            assertIs<AppResult.Ok<*>>(result)
            assertEquals("""{"locale":"fr","region":null}""", sent)
            assertEquals("fr", repo.session.value?.locale)
            assertNull(repo.session.value?.region)
        }

    @Test
    fun anOfflineProfileChangeShowsAtOnceAndIsSentAtTheNextLaunch() =
        runTest {
            val store = MemorySecureStore()
            signedIn(store)
            val offline =
                AuthRepositoryImpl(
                    KtorAuthRemoteDataSource(testHttpClient { throw IllegalStateException("offline") }),
                    SecureSessionLocalDataSource(store),
                )
            offline.updateProfile(ProfileChange(locale = "fr"))
            offline.updateProfile(ProfileChange(onboardingCompleted = true))
            assertEquals("fr", offline.session.value?.locale)
            assertFalse(offline.session.value!!.onboardingDue)

            val sent = mutableListOf<String>()
            val online =
                AuthRepositoryImpl(
                    KtorAuthRemoteDataSource(
                        testHttpClient { request ->
                            if (request.method == HttpMethod.Patch) sent += request.text()
                            respondJson("""{"data":$me}""")
                        },
                    ),
                    SecureSessionLocalDataSource(store),
                )
            online.restore()
            assertEquals(listOf("""{"locale":"fr","onboardingCompleted":true}"""), sent)
            assertNull(SecureSessionLocalDataSource(store).pendingProfile())
        }

    @Test
    fun aRefusedProfileChangeRestoresTheServersProfile() =
        runTest {
            val store = MemorySecureStore()
            signedIn(store)
            val repo =
                AuthRepositoryImpl(
                    KtorAuthRemoteDataSource(
                        testHttpClient {
                            respondJson("""{"code":422,"message":"Invalid.","data":{"error":"VALIDATION"}}""", HttpStatusCode.UnprocessableEntity)
                        },
                    ),
                    SecureSessionLocalDataSource(store),
                )
            val result = repo.updateProfile(ProfileChange(region = Change.Set("ZZ")))
            assertEquals(AppError.Rejected("VALIDATION", "Invalid."), (result as AppResult.Err).error)
            assertEquals("FR", repo.session.value?.region)
            assertNull(SecureSessionLocalDataSource(store).pendingProfile())
        }

    @Test
    fun aWrongPasswordIsARefusalNotAnEndedSession() =
        runTest {
            val store = MemorySecureStore()
            signedIn(store)
            var cleared = false
            val repo =
                AuthRepositoryImpl(
                    KtorAuthRemoteDataSource(
                        testHttpClient {
                            respondJson("""{"code":401,"message":"Wrong password.","data":{"error":"WRONG_PASSWORD"}}""", HttpStatusCode.Unauthorized)
                        },
                    ),
                    SecureSessionLocalDataSource(store),
                ) { cleared = true }
            val result = repo.changePassword("old-password-1", "new-password-12")
            assertEquals("WRONG_PASSWORD", ((result as AppResult.Err).error as AppError.Rejected).code)
            assertFalse(cleared)
            assertEquals("t1", store.values[SecureKeys.SESSION_TOKEN])
        }

    @Test
    fun deletionIsScheduledThenCancelledOnTheSession() =
        runTest {
            val store = MemorySecureStore()
            signedIn(store)
            var sent = ""
            val repo =
                AuthRepositoryImpl(
                    KtorAuthRemoteDataSource(
                        testHttpClient { request ->
                            sent = request.text()
                            if (request.method == HttpMethod.Delete) {
                                respondJson("", HttpStatusCode.NoContent)
                            } else {
                                respondJson("""{"data":{"scheduledFor":"2026-11-21T14:03:00Z"}}""")
                            }
                        },
                    ),
                    SecureSessionLocalDataSource(store),
                )
            repo.restore()
            assertEquals(AppResult.Ok(1_795_269_780_000L), repo.scheduleDeletion("my-password-12"))
            assertEquals("""{"password":"my-password-12"}""", sent)
            assertEquals(1_795_269_780_000L, repo.session.value?.deletionScheduledAtMillis)
            repo.cancelDeletion()
            assertNull(repo.session.value?.deletionScheduledAtMillis)
        }

    @Test
    fun devicesListTheCurrentOneFirstThenTheLatestUsed() =
        runTest {
            val repo =
                AuthRepositoryImpl(
                    KtorAuthRemoteDataSource(
                        testHttpClient {
                            respondJson(
                                """{"data":[""" +
                                    """{"id":"a","client":"web","current":false,"createdAt":"2026-10-01T00:00:00Z","lastUsedAt":"2026-10-20T00:00:00Z","device":"Mac · Chrome"},""" +
                                    """{"id":"b","client":"mobile","current":true,"createdAt":"2026-10-02T00:00:00Z","device":"iPhone"},""" +
                                    """{"id":"c","client":"web","current":false,"createdAt":"2026-10-03T00:00:00Z","lastUsedAt":"2026-10-21T00:00:00Z","ip":"203.0.113.0","device":"PC · Firefox"}]}""",
                            )
                        },
                    ),
                    SecureSessionLocalDataSource(MemorySecureStore()),
                )
            val list = (repo.sessions() as AppResult.Ok).value
            assertEquals(listOf("b", "c", "a"), list.map { it.id })
            assertEquals("203.0.113.0", list[1].ip)
        }
}

private object ApiJsonUser {
    fun decode(json: String): UserDto = com.vovoscan.core.network.ApiJson.decodeFromString(UserDto.serializer(), json)
}
