package com.vovoscan.core.network

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals

class ApiCallTest {
    @Serializable
    data class Pong(
        val ok: Boolean,
    )

    private fun client(
        status: HttpStatusCode,
        body: String,
    ) = createHttpClient(
        MockEngine { respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) },
        ApiConfig("https://api.test/"),
    ) { "token" }

    @Test
    fun succesDecode() =
        runTest {
            assertEquals(AppResult.Ok(Pong(true)), client(HttpStatusCode.OK, """{"ok":true}""").apiCall<Pong> { get("ping") })
        }

    @Test
    fun refusMetierGardeLeCode() =
        runTest {
            val result =
                client(HttpStatusCode.Conflict, """{"code":409,"message":"Invite already used.","data":{"error":"INVITE_INVALID"}}""")
                    .apiCall<Pong> { get("ping") }
            assertEquals(AppResult.Err(AppError.Rejected("INVITE_INVALID", "Invite already used.")), result)
        }

    @Test
    fun interditDevientForbidden() =
        runTest {
            assertEquals(AppResult.Err(AppError.Forbidden), client(HttpStatusCode.Forbidden, "{}").apiCall<Pong> { get("ping") })
        }

    @Test
    fun mauvaisIdentifiantsGardentLeMessageDuServeur() =
        runTest {
            val body = """{"code":401,"message":"Wrong email or password.","data":{"error":"INVALID_CREDENTIALS"}}"""
            val result = client(HttpStatusCode.Unauthorized, body).apiCall<Pong> { get("ping") }
            assertEquals(AppResult.Err(AppError.Rejected("INVALID_CREDENTIALS", "Wrong email or password.")), result)
        }

    @Test
    fun sessionFinie() =
        runTest {
            val body = """{"code":401,"message":"Sign in to continue.","data":{"error":"UNAUTHORIZED"}}"""
            assertEquals(AppResult.Err(AppError.Unauthorized), client(HttpStatusCode.Unauthorized, body).apiCall<Pong> { get("ping") })
        }
}
