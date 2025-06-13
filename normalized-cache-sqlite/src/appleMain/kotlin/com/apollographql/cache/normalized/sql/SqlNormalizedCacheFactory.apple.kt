package com.apollographql.cache.normalized.sql

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.cash.sqldelight.driver.native.wrapConnection
import co.touchlab.sqliter.DatabaseConfiguration
import com.apollographql.cache.normalized.api.NormalizedCacheFactory
import com.apollographql.cache.normalized.sql.internal.record.SqlRecordDatabase

/**
 * @param name the name of the database or null for an in-memory database
 * @param baseDir the baseDirectory where to store the database.
 * [baseDir] must exist and be a directory
 * If [baseDir] is a relative path, it will be interpreted relative to the current working directory
 */
fun SqlNormalizedCacheFactory(
    name: String?,
    baseDir: String?,
): NormalizedCacheFactory = SqlNormalizedCacheFactory(createDriver(name, baseDir))

actual fun SqlNormalizedCacheFactory(name: String?): NormalizedCacheFactory = SqlNormalizedCacheFactory(name, null)

private fun createDriver(name: String?, baseDir: String?): SqlDriver {
  val schema = SqlRecordDatabase.Schema.synchronous()
  val databaseConfiguration = DatabaseConfiguration(
      name = name ?: "memoryDb",
      inMemory = name == null,
      version = schema.version.toInt(),
      create = { connection ->
        wrapConnection(connection) { schema.create(it) }
      },
      upgrade = { connection, oldVersion, newVersion ->
        wrapConnection(connection) { schema.migrate(it, oldVersion.toLong(), newVersion.toLong()) }
      },
      extendedConfig = DatabaseConfiguration.Extended(
          basePath = baseDir
      )
  )
  return NativeSqliteDriver(databaseConfiguration, 1)
}
