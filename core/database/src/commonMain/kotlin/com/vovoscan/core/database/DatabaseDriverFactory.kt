package com.vovoscan.core.database

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema

/**
 * Ouvre la base SQLDelight d'une feature (une base par feature, ses tables et ses migrations).
 * La base locale est la source de lecture des écrans (hors ligne d'abord) ; le serveur reste la source de vérité.
 */
expect class DatabaseDriverFactory {
    fun create(
        schema: SqlSchema<QueryResult.Value<Unit>>,
        name: String,
    ): SqlDriver
}
