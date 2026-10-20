package com.vovoscan.core.testing

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema

/** In-memory SQLDelight database with the schema created: JDBC on the JVM, native driver on iOS. */
expect fun inMemoryDriver(schema: SqlSchema<QueryResult.Value<Unit>>): SqlDriver
