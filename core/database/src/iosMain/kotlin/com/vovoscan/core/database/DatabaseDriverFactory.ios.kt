package com.vovoscan.core.database

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseFileContext
import platform.Foundation.NSLog

actual class DatabaseDriverFactory {
    /** A database left by a newer build is deleted and created again rather than stopping the app (`DowngradeGuard`). */
    actual fun create(
        schema: SqlSchema<QueryResult.Value<Unit>>,
        name: String,
    ): SqlDriver =
        DowngradeGuard.open(
            open = {
                NativeSqliteDriver(schema, name).also { driver ->
                    // The version check runs when a connection opens: open one now, inside the guard.
                    driver.executeQuery(null, "PRAGMA user_version", { QueryResult.Value(Unit) }, 0)
                }
            },
            reset = { DatabaseFileContext.deleteDatabase(name) },
            onReset = { NSLog("vovoscan: %@ was made by a newer version of the app; rebuilt", name) },
        )
}
