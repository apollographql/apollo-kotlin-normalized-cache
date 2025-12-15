package com.apollographql.cache.normalized.memory

import com.apollographql.cache.normalized.api.ApolloCacheHeaders
import com.apollographql.cache.normalized.api.CacheHeaders
import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.api.NormalizedCache
import com.apollographql.cache.normalized.api.NormalizedCacheFactory
import com.apollographql.cache.normalized.api.Record
import com.apollographql.cache.normalized.api.RecordMerger
import com.apollographql.cache.normalized.api.RecordMergerContext
import com.apollographql.cache.normalized.api.withDates
import com.apollographql.cache.normalized.api.withSizeInBytes
import com.apollographql.cache.normalized.internal.withReentrantLock
import com.apollographql.cache.normalized.memory.internal.LruCache
import com.apollographql.cache.normalized.memory.internal.RecordWeigher
import kotlinx.coroutines.sync.Mutex
import kotlin.reflect.KClass

/**
 * Memory (multiplatform) cache implementation based on recently used property (LRU).
 *
 * [maxSizeBytes] - the maximum size in bytes the cache may occupy.
 * [expireAfterMillis] - after what timeout each entry in the cache treated as expired. By default there is no timeout.
 *
 * Expired entries removed from the cache only on cache miss ([loadRecord] operation) and not removed from the cache automatically
 * (there is no any sort of GC that runs in the background).
 */
class MemoryCache(
    override val nextCache: NormalizedCache? = null,
    private val maxSizeBytes: Int = Int.MAX_VALUE,
    private val expireAfterMillis: Long = -1,
) : NormalizedCache {
  private val mutex = Mutex()

  private suspend fun <T> withLock(block: suspend () -> T): T {
    return mutex.withReentrantLock { block() }
  }

  private val lruCache = LruCache<CacheKey, Record>(maxSize = maxSizeBytes, expireAfterMillis = expireAfterMillis) { key, record ->
    key.key.length + sizeOfRecord(record)
  }

  override suspend fun loadRecord(key: CacheKey, cacheHeaders: CacheHeaders): Record? = withLock {
    val record = lruCache[key]
    record ?: nextCache?.loadRecord(key, cacheHeaders)?.also { nextCachedRecord ->
      lruCache[key] = nextCachedRecord
    }
  }

  override suspend fun loadRecords(keys: Collection<CacheKey>, cacheHeaders: CacheHeaders): Collection<Record> = withLock {
    val recordsByKey: Map<CacheKey, Record?> = keys.associateWith { key -> lruCache[key] }
    val missingKeys = recordsByKey.filterValues { it == null }.keys
    val nextCachedRecords = nextCache?.loadRecords(missingKeys, cacheHeaders).orEmpty()
    for (record in nextCachedRecords) {
      lruCache[record.key] = record
    }
    recordsByKey.values.filterNotNull() + nextCachedRecords
  }

  override suspend fun clearAll() {
    withLock {
      lruCache.clear()
      nextCache?.clearAll()
    }
  }

  override suspend fun remove(cacheKey: CacheKey, cascade: Boolean): Boolean {
    return remove(cacheKeys = listOf(cacheKey), cascade = cascade) > 0
  }

  override suspend fun remove(cacheKeys: Collection<CacheKey>, cascade: Boolean): Int {
    return withLock {
      val total = internalRemove(cacheKeys, cascade)
      nextCache?.remove(cacheKeys, cascade)
      total
    }
  }

  private fun internalRemove(cacheKeys: Collection<CacheKey>, cascade: Boolean): Int {
    var total = 0
    val referencedCacheKeys = mutableSetOf<CacheKey>()
    for (cacheKey in cacheKeys) {
      val removedRecord = lruCache.remove(cacheKey)
      if (cascade && removedRecord != null) {
        referencedCacheKeys += removedRecord.referencedFields()
      }
      if (removedRecord != null) {
        total++
      }
    }
    if (referencedCacheKeys.isNotEmpty()) {
      total += internalRemove(referencedCacheKeys, cascade)
    }
    return total
  }

  override suspend fun merge(record: Record, cacheHeaders: CacheHeaders, recordMerger: RecordMerger): Set<String> {
    return merge(records = listOf(record), cacheHeaders = cacheHeaders, recordMerger = recordMerger)
  }

  override suspend fun merge(records: Collection<Record>, cacheHeaders: CacheHeaders, recordMerger: RecordMerger): Set<String> {
    if (cacheHeaders.hasHeader(ApolloCacheHeaders.DO_NOT_STORE)) {
      return emptySet()
    }
    val receivedDate = cacheHeaders.headerValue(ApolloCacheHeaders.RECEIVED_DATE)
    val expirationDate = cacheHeaders.headerValue(ApolloCacheHeaders.EXPIRATION_DATE)
    return withLock {
      val existingRecords = loadRecords(records.map { it.key }, cacheHeaders).associateBy { it.key }
      val recordsToInsert = mutableListOf<Record>()
      val changedKeys = records.flatMap { record ->
        val existingRecord = existingRecords[record.key]
        if (existingRecord == null) {
          val record = record.withDates(receivedDate = receivedDate, expirationDate = expirationDate)
          recordsToInsert.add(record)
          lruCache[record.key] = record
          record.fieldKeys()
        } else {
          val (mergedRecord, changedKeys) = recordMerger.merge(RecordMergerContext(existing = existingRecord, incoming = record, cacheHeaders = cacheHeaders))
          val mergedRecordWithDates = mergedRecord.withDates(receivedDate = receivedDate, expirationDate = expirationDate)
          recordsToInsert.add(mergedRecordWithDates)
          lruCache[record.key] = mergedRecordWithDates
          changedKeys
        }
      }.toSet()
      nextCache?.merge(
          records = recordsToInsert,
          cacheHeaders = cacheHeaders.newBuilder()
              // Skip merging in the next cache as we already did it here
              .addHeader(ApolloCacheHeaders.SKIP_MERGE, "true")
              .build(),
          recordMerger = recordMerger,
      )
      changedKeys
    }
  }

  override suspend fun dump(): Map<KClass<*>, Map<CacheKey, Record>> {
    return withLock {
      mapOf(this::class to lruCache.asMap().mapValues { (_, record) -> record.withSizeInBytes(sizeOfRecord(record)) }) +
          nextCache?.dump().orEmpty()
    }
  }

  override fun sizeOfRecord(record: Record): Int {
    return RecordWeigher.calculateBytes(record)
  }

  override suspend fun size(): Long {
    return withLock { lruCache.weight() }.toLong()
  }

  internal fun clearCurrentCache() {
    lruCache.clear()
  }

  override suspend fun trim(maxSizeBytes: Long, trimFactor: Float): Long {
    return if (nextCache == null) {
      -1
    } else {
      withLock { nextCache.trim(maxSizeBytes, trimFactor) }
    }
  }
}

class MemoryCacheFactory(
    private val maxSizeBytes: Int = Int.MAX_VALUE,
    private val expireAfterMillis: Long = -1,
) : NormalizedCacheFactory() {

  private var nextCacheFactory: NormalizedCacheFactory? = null

  fun chain(factory: NormalizedCacheFactory): MemoryCacheFactory {
    nextCacheFactory = factory
    return this
  }

  override fun create(): MemoryCache {
    return MemoryCache(
        nextCache = nextCacheFactory?.create(),
        maxSizeBytes = maxSizeBytes,
        expireAfterMillis = expireAfterMillis,
    )
  }
}
