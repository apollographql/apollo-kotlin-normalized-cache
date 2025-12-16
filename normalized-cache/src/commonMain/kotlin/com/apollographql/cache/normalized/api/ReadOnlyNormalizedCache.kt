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

  /**
   * Returns the size in bytes of a [Record].
   * This is an optional operation that can be implemented by caches for debug purposes, otherwise it defaults to `-1`, meaning unknown size.
   */
  fun sizeOfRecord(record: Record): Int = -1

  /**
   * Returns the total size in bytes of this cache.
   * This is an optional operation that can be implemented by caches for debug or observability purposes, otherwise it defaults to `-1`, meaning unknown size.
   *
   * Note: in case of a chained cache, this size does not include the size of the next cache in the chain. See [nextCache].
   */
  suspend fun size(): Long = -1L

  /**
   * Some cache implementations (e.g. [com.apollographql.cache.normalized.internal.OptimisticNormalizedCache] and [com.apollographql.cache.normalized.memory.MemoryCache]) support chaining caches.
   * For those, this returns the next cache in the chain or `null` if there is none. Implementations that don't support chaining return `null`.
   */
  val nextCache: ReadOnlyNormalizedCache?
    get() = null

  suspend fun close() {}
}
