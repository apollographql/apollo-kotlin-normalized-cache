package com.apollographql.cache.normalized.sql.internal

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.apollographql.cache.normalized.sql.internal.record.SqlRecordDatabase

private const val versionPragma = "user_version"

internal actual suspend fun maybeCreateOrMigrateSchema(driver: SqlDriver) {
  val oldVersion = driver.executeQuery(
      null,
      "PRAGMA $versionPragma",
      { cursor ->
        val ret = if (cursor.next().value) {
          cursor.getLong(0)?.toInt()
        } else {
          null
        }
        QueryResult.Value(ret ?: 0)
      },
      0
  ).value.toLong()

  val schema = SqlRecordDatabase.Schema.synchronous()
  val newVersion = schema.version

  if (oldVersion == 0L) {
    schema.create(driver)
    driver.execute(null, "PRAGMA $versionPragma=$newVersion", 0)
  } else if (oldVersion < newVersion) {
    schema.migrate(driver, oldVersion, newVersion)
    driver.execute(null, "PRAGMA $versionPragma=$newVersion", 0)
  }
}

internal actual val parametersMax: Int = 999
