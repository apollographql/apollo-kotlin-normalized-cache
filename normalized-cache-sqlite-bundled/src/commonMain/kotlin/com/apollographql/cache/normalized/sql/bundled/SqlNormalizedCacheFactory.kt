package com.apollographql.cache.normalized.sql.bundled

import com.apollographql.cache.normalized.api.NormalizedCache
import com.apollographql.cache.normalized.api.NormalizedCacheFactory
import com.apollographql.cache.normalized.sql.bundled.internal.RecordDatabase

/**
 * Creates a new [NormalizedCacheFactory] that uses a persistent cache based on Sqlite
 *
 * @param name The name of the database or null for an in-memory database
 * When not in memory, the database will be stored in a platform specific folder
 * - on Android it will use [Context.getCacheDir](https://developer.android.com/reference/android/content/Context#getCacheDir()). It can
 * also be an absolute path to a file.
 * - on MacOS, it will use "Application Support/databases/name"
 * - on the JVM, it will use "System.getProperty("user.home")/.apollo"
 * - on JS/Wasm, this argument is unused
 *
 * Default: "apollo.db"
 */
fun SqlNormalizedCacheFactory(name: String? = "apollo.db"): NormalizedCacheFactory {
  return object : NormalizedCacheFactory() {
    override fun create(): NormalizedCache {
      return SqlNormalizedCache(RecordDatabase(getFileName(name)))
    }
  }
}

internal expect fun getFileName(name: String?): String?
