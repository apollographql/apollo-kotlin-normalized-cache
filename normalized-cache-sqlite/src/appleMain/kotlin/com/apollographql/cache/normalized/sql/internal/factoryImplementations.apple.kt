package com.apollographql.cache.normalized.sql.internal

import app.cash.sqldelight.db.SqlDriver

internal actual suspend fun maybeCreateOrMigrateSchema(driver: SqlDriver) {
  // no op
}

internal actual val parametersMax: Int = 999
