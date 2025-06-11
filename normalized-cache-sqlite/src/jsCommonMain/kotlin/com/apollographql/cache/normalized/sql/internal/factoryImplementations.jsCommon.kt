package com.apollographql.cache.normalized.sql.internal

import app.cash.sqldelight.async.coroutines.awaitCreate
import app.cash.sqldelight.db.SqlDriver
import com.apollographql.cache.normalized.sql.internal.record.SqlRecordDatabase

internal actual suspend fun maybeCreateOrMigrateSchema(driver: SqlDriver) {
  // TODO: migrate instead of creating if needed
  SqlRecordDatabase.Schema.awaitCreate(driver)
}

internal actual val parametersMax: Int = 999
