package com.vovoscan.core.database

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DowngradeGuardTest {
    @Test
    fun recognisesBothDriversRefusals() {
        assertTrue(DowngradeGuard.isDowngrade(IllegalStateException("Database version 4 newer than config version 3")))
        assertTrue(
            DowngradeGuard.isDowngrade(RuntimeException("open", IllegalStateException("Can't downgrade database from version 4 to 3"))),
        )
        assertFalse(DowngradeGuard.isDowngrade(IllegalStateException("disk I/O error")))
    }

    @Test
    fun aNewerDatabaseIsDeletedAndOpenedAgain() {
        var version = 4
        val events = mutableListOf<String>()
        val opened =
            DowngradeGuard.open(
                open = {
                    events += "open"
                    check(version <= 3) { "Database version $version newer than config version 3" }
                    "driver"
                },
                reset = {
                    events += "reset"
                    version = 0
                },
                onReset = { events += "logged" },
            )
        assertEquals("driver", opened)
        assertEquals(listOf("open", "logged", "reset", "open"), events)
    }

    @Test
    fun otherErrorsAreNotHidden() {
        var resets = 0
        assertFailsWith<IllegalStateException> {
            DowngradeGuard.open(open = { error("disk I/O error") }, reset = { resets++ })
        }
        assertEquals(0, resets)
    }

    @Test
    fun aDatabaseStillRefusedAfterTheResetIsTheCallers() {
        assertFailsWith<IllegalStateException> {
            DowngradeGuard.open(open = { error("Database version 4 newer than config version 3") }, reset = {})
        }
    }
}
