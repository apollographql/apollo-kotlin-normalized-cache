package test

import com.apollographql.cache.normalized.api.ApolloCacheHeaders
import com.apollographql.cache.normalized.api.CacheHeaders
import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.api.DefaultRecordMerger
import com.apollographql.cache.normalized.api.NormalizedCacheFactory
import com.apollographql.cache.normalized.api.Record
import com.apollographql.cache.normalized.api.receivedDate
import com.apollographql.cache.normalized.memory.MemoryCacheFactory
import com.apollographql.cache.normalized.testing.SqlNormalizedCacheFactory
import com.apollographql.cache.normalized.testing.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MergeTest {
  @Test
  fun mergeDatesMemoryCache() {
    mergeDates(MemoryCacheFactory())
  }

  @Test
  fun mergeDatesSqlCache() {
    mergeDates(SqlNormalizedCacheFactory())
  }

  @Test
  fun mergeDatesChainedCache() {
    mergeDates(MemoryCacheFactory().chain(SqlNormalizedCacheFactory()))
  }

  private fun mergeDates(normalizedCacheFactory: NormalizedCacheFactory) = runTest {
    val normalizedCache = normalizedCacheFactory.create()
    try {
      normalizedCache.merge(
          record = Record(
              key = CacheKey("Key"),
              fields = mapOf("field1" to "value1"),
          ),
          cacheHeaders = CacheHeaders.Builder().addHeader(ApolloCacheHeaders.RECEIVED_DATE, "1").build(),
          recordMerger = DefaultRecordMerger
      )
      normalizedCache.merge(
          record = Record(
              key = CacheKey("Key"),
              fields = mapOf("field2" to "value2"),
          ),
          cacheHeaders = CacheHeaders.Builder().addHeader(ApolloCacheHeaders.RECEIVED_DATE, "2").build(),
          recordMerger = DefaultRecordMerger
      )
      normalizedCache.loadRecord(
          key = CacheKey("Key"),
          cacheHeaders = CacheHeaders.NONE,
      )!!.let { record ->
        assertEquals(1, record.receivedDate("field1"))
        assertEquals(2, record.receivedDate("field2"))
      }
    } finally {
      normalizedCache.close()
    }
  }
}
