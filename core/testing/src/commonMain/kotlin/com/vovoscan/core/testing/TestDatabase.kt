package com.vovoscan.core.testing

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema

/** Base SQLDelight en mémoire, schéma créé : JDBC sur la JVM, pilote natif sur iOS. */
expect fun inMemoryDriver(schema: SqlSchema<QueryResult.Value<Unit>>): SqlDriver
