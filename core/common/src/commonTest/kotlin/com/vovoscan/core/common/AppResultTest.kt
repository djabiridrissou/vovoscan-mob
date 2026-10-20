package com.vovoscan.core.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppResultTest {
    @Test
    fun mapKeepsTheError() {
        val err: AppResult<Int> = AppResult.Err(AppError.Network)
        assertEquals(err, err.map { it + 1 })
        assertNull(err.getOrNull())
    }

    @Test
    fun flatMapChains() {
        assertEquals(AppResult.Ok(3), AppResult.Ok(1).flatMap { AppResult.Ok(it + 2) })
    }
}
