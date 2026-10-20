package com.vovoscan.core.database

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema

/**
 * Opens a feature's SQLDelight database (one database per feature, with its tables and migrations).
 * The local database is what screens read from (offline first); the server remains the source of truth.
 */
expect class DatabaseDriverFactory {
    fun create(
        schema: SqlSchema<QueryResult.Value<Unit>>,
        name: String,
    ): SqlDriver
}
