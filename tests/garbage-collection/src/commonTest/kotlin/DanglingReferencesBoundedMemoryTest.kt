package test

import com.apollographql.cache.normalized.api.CacheHeaders
import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.api.NormalizedCache
import com.apollographql.cache.normalized.api.Record
import com.apollographql.cache.normalized.api.RecordMerger
import com.apollographql.cache.normalized.removeDanglingReferences
import com.apollographql.cache.normalized.testing.runTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `removeDanglingReferences()` must never hold more than ~`batchSize` records in memory, however large the cache.
 * This simulates a huge cache (records generated lazily, each with one dangling reference) and checks that remove()/
 * merge() are never called with more than `batchSize` records at a time.
 */
class DanglingReferencesBoundedMemoryTest {
  @Test
  fun neverBuffersMoreThanBatchSize() = runTest {
    val recordCount = 1_000_000
    val batchSize = 100
    val cache = DanglingFakeLargeCache(recordCount)

    val result = cache.removeDanglingReferences(batchSize = batchSize)

    assertEquals(recordCount, result.removedRecords.size)
    assertTrue(cache.largestBatchSize <= batchSize, "largest batch size was ${cache.largestBatchSize}")
  }
}

private class DanglingFakeLargeCache(private val recordCount: Int) : NormalizedCache {
  // Keep track of the largest collection size passed to remove()/merge()
  var largestBatchSize = 0
    private set

  private val removed = HashSet<CacheKey>()

  override suspend fun loadAllRecords(batchSize: Int): Flow<Record> = flow {
    for (i in 0 until recordCount) {
      val key = CacheKey("R$i")
      if (key !in removed) {
        emit(Record(key = key, fields = mapOf("__typename" to "Type", "ref" to CacheKey("missing"))))
      }
    }
  }

  override suspend fun remove(cacheKeys: Collection<CacheKey>, cascade: Boolean): Int {
    largestBatchSize = maxOf(largestBatchSize, cacheKeys.size)
    return cacheKeys.count { removed.add(it) }
  }

  override suspend fun merge(records: Collection<Record>, cacheHeaders: CacheHeaders, recordMerger: RecordMerger): Set<String> {
    largestBatchSize = maxOf(largestBatchSize, records.size)
    return emptySet()
  }

  override suspend fun loadRecord(key: CacheKey, cacheHeaders: CacheHeaders): Record? = null
  override suspend fun loadRecords(keys: Collection<CacheKey>, cacheHeaders: CacheHeaders): Collection<Record> = emptyList()
  override suspend fun dump(): Map<KClass<*>, Map<CacheKey, Record>> = emptyMap()
  override suspend fun merge(record: Record, cacheHeaders: CacheHeaders, recordMerger: RecordMerger) =
    merge(listOf(record), cacheHeaders, recordMerger)

  override suspend fun clearAll() {}
  override suspend fun remove(cacheKey: CacheKey, cascade: Boolean) = remove(listOf(cacheKey), cascade) > 0
}
