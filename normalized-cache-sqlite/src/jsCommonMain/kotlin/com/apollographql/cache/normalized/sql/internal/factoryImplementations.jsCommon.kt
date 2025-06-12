package com.apollographql.cache.normalized.sql.internal

import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.async.coroutines.awaitCreate
import app.cash.sqldelight.async.coroutines.awaitMigrate
import app.cash.sqldelight.async.coroutines.awaitQuery
import app.cash.sqldelight.db.SqlDriver
import com.apollographql.cache.normalized.sql.internal.record.SqlRecordDatabase

private const val versionPragma = "user_version"

internal actual suspend fun maybeCreateOrMigrateSchema(driver: SqlDriver) {
  val oldVersion = driver.awaitQuery(
      null,
      "PRAGMA $versionPragma",
      { cursor ->
        val ret = if (cursor.next().await()) {
          cursor.getLong(0)!!
        } else {
          null
        }
        ret
      },
      0
  ) ?: 0L
  val schema = SqlRecordDatabase.Schema
  val newVersion = schema.version
  if (oldVersion == 0L) {
    schema.awaitCreate(driver)
    driver.await(null, "PRAGMA $versionPragma=$newVersion", 0)
  } else if (oldVersion < newVersion) {
    schema.awaitMigrate(driver, oldVersion, newVersion)
    driver.await(null, "PRAGMA $versionPragma=$newVersion", 0)
  }
}

internal actual val parametersMax: Int = 999
