package com.apollographql.cache.normalized.api

import kotlin.jvm.JvmSuppressWildcards
import kotlin.reflect.KClass

interface ReadOnlyNormalizedCache {
  /**
   * @param key          The key of the record to read.
   * @param cacheHeaders The cache headers associated with the request which generated this record.
   * @return The [Record] for key. If not present return null.
   */
  suspend fun loadRecord(key: CacheKey, cacheHeaders: CacheHeaders): Record?

  /**
   * Calls through to [NormalizedCache.loadRecord]. Implementations should override this
   * method if the underlying storage technology can offer an optimized manner to read multiple records.
   * There is no guarantee on the order of returned [Record]
   *
   * @param keys         The set of [Record] keys to read.
   * @param cacheHeaders The cache headers associated with the request which generated this record.
   */
  suspend fun loadRecords(keys: Collection<CacheKey>, cacheHeaders: CacheHeaders): Collection<Record>

  suspend fun dump(): Map<@JvmSuppressWildcards KClass<*>, Map<CacheKey, Record>>
}
