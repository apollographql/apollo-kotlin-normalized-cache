package com.apollographql.cache.normalized.sql

import app.cash.sqldelight.db.SqlDriver
import com.apollographql.cache.normalized.api.NormalizedCache
import com.apollographql.cache.normalized.api.NormalizedCacheFactory
import com.apollographql.cache.normalized.sql.internal.RecordDatabase

/**
 * Creates a new [NormalizedCacheFactory] that uses a persistent cache based on Sqlite
 *
 * @param name: the name of the database or null for an in-memory database
 * When not in memory, the database will be stored in a platform specific folder
 * - on Android it will use [Context.getDatabaseName](https://developer.android.com/reference/android/content/Context#getDatabasePath(java.lang.String))
 * - on MacOS, it will use "Application Support/databases/name"
 * - on the JVM, it will use "System.getProperty("user.home")/.apollo"
 * - on JS/Wasm, this argument is unused
 * Default: "apollo.db"
 */
expect fun SqlNormalizedCacheFactory(name: String? = "apollo.db"): NormalizedCacheFactory

fun SqlNormalizedCacheFactory(driver: SqlDriver): NormalizedCacheFactory = object : NormalizedCacheFactory() {
  override fun create(): NormalizedCache {
    return SqlNormalizedCache(RecordDatabase(driver))
  }
}
