package com.apollographql.cache.normalized.rocksdb

import com.apollographql.cache.normalized.api.NormalizedCache
import com.apollographql.cache.normalized.api.NormalizedCacheFactory
import com.apollographql.cache.normalized.rocksdb.internal.RecordDatabase

/**
 * Creates a new [NormalizedCacheFactory] that uses a persistent cache based on Sqlite
 *
 * @param name The name of the database folder.
 * If the path is absolute, it will be used as is, otherwise the database will be stored in a platform specific folder:
 * - on Android it will use [Context.getCacheDir](https://developer.android.com/reference/android/content/Context#getCacheDir())
 * - on MacOS, it will use "Application Support/databases/name"
 * - on the JVM, it will use "System.getProperty("user.home")/.apollo"
 * - on JS/Wasm, this argument is unused
 *
 * Default: "apollo"
 */
fun RocksDBNormalizedCacheFactory(name: String = "apollo"): NormalizedCacheFactory {
  return object : NormalizedCacheFactory() {
    override fun create(): NormalizedCache {
      return RocksDBNormalizedCache(RecordDatabase(getFileName(name)))
    }
  }
}

internal expect fun getFileName(name: String): String
