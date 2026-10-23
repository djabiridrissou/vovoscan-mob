package com.vovoscan.core.database

import android.content.Context
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

actual class DatabaseDriverFactory(
    private val context: Context,
) {
    actual fun create(
        schema: SqlSchema<QueryResult.Value<Unit>>,
        name: String,
    ): SqlDriver = AndroidSqliteDriver(schema, context, name, callback = RebuildOnDowngrade(schema, name))
}

/**
 * A database left by a newer build of the app (installed over by an older one) is emptied and created again: the
 * framework's default refuses it ("Can't downgrade database") at the first query (`DowngradeGuard`).
 */
private class RebuildOnDowngrade(
    schema: SqlSchema<QueryResult.Value<Unit>>,
    private val name: String,
) : AndroidSqliteDriver.Callback(schema) {
    override fun onDowngrade(
        db: SupportSQLiteDatabase,
        oldVersion: Int,
        newVersion: Int,
    ) {
        Log.w("vovoscan", "$name was made by a newer version of the app ($oldVersion > $newVersion); rebuilt")
        val objects =
            db.query("SELECT type, name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' AND type IN ('table', 'view')").use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) }
            }
        objects.forEach { (type, item) -> db.execSQL("DROP ${type.uppercase()} IF EXISTS \"${item.replace("\"", "\"\"")}\"") }
        onCreate(db)
    }
}
