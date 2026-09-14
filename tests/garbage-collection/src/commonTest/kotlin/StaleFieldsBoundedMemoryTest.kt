package test

import com.apollographql.cache.normalized.api.ApolloCacheHeaders
import com.apollographql.cache.normalized.api.CacheHeaders
import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.api.GlobalMaxAgeProvider
import com.apollographql.cache.normalized.api.NormalizedCache
import com.apollographql.cache.normalized.api.Record
import com.apollographql.cache.normalized.api.RecordMerger
import com.apollographql.cache.normalized.removeStaleFields
import com.apollographql.cache.normalized.testing.runTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * `removeStaleFields()` must never hold more than ~`batchSize` records in memory, however large the cache. This
 * simulates a huge cache (records generated lazily) and checks that remove()/ merge() are never called with more than `batchSize` records
 * at a time.
 */
class StaleFieldsBoundedMemoryTest {
  @Test
  fun neverBuffersMoreThanBatchSize() = runTest {
    val recordCount = 1_000_000
    val batchSize = 100
    val cache = FakeLargeCache(recordCount)

    val result = cache.removeStaleFields(
        maxAgeProvider = GlobalMaxAgeProvider(Duration.ZERO),
        batchSize = batchSize,
        clock = { 1_000_000_000_000L },
    )

    assertEquals(recordCount, result.removedRecords.size)
    assertTrue(cache.largestBatchSize <= batchSize, "largest batch size was ${cache.largestBatchSize}")
  }
}

private class FakeLargeCache(private val recordCount: Int) : NormalizedCache {
  // Keep track of the largest collection size passed to remove()/merge()
  var largestBatchSize = 0
    private set

  override suspend fun loadAllRecords(batchSize: Int): Flow<Record> = flow {
    repeat(recordCount) { i ->
      emit(
          Record(
              key = CacheKey("R$i"),
              fields = mapOf("__typename" to "Type", "field" to "value"),
              metadata = mapOf("field" to mapOf(ApolloCacheHeaders.RECEIVED_DATE to 0L)),
          ),
      )
    }
  }

  override suspend fun remove(cacheKeys: Collection<CacheKey>, cascade: Boolean): Int {
    largestBatchSize = maxOf(largestBatchSize, cacheKeys.size)
    return cacheKeys.size
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
