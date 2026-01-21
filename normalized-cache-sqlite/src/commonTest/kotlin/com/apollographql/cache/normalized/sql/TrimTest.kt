package com.apollographql.cache.normalized.sql

import com.apollographql.cache.normalized.CacheManager
import com.apollographql.cache.normalized.api.CacheHeaders
import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.api.DefaultCacheKeyGenerator
import com.apollographql.cache.normalized.api.DefaultCacheResolver
import com.apollographql.cache.normalized.api.DefaultRecordMerger
import com.apollographql.cache.normalized.api.Record
import com.apollographql.cache.normalized.api.withDates
import com.apollographql.cache.normalized.testing.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrimTest {
  @Test
  fun trimTest() = runTest {
    val cacheManager = CacheManager(SqlNormalizedCacheFactory(), DefaultCacheKeyGenerator, DefaultCacheResolver).also { it.clearAll() }

    val largeString = "".padStart(1024, '?')

    val oldRecord = Record(
        key = CacheKey("old"),
        fields = mapOf("key" to "value"),
        mutationId = null,
        metadata = emptyMap()
    )
    cacheManager.accessCache { it.merge(oldRecord, CacheHeaders.NONE, recordMerger = DefaultRecordMerger) }

    val newRecords = 0.until(2 * 1024).map {
      Record(
          key = CacheKey("new$it"),
          fields = mapOf("key" to largeString),
          mutationId = null,
          metadata = emptyMap()
      ).withDates(receivedDate = it.toString(), expirationDate = null)
    }
    cacheManager.accessCache { it.merge(newRecords, CacheHeaders.NONE, recordMerger = DefaultRecordMerger) }

    val sizeBeforeTrim = cacheManager.trim(-1, 0.1f)
    assertEquals(2572288, sizeBeforeTrim)

    // Trim the cache by 10%
    val sizeAfterTrim = cacheManager.trim(2572288, 0.1f)

    assertEquals(2318336, sizeAfterTrim)
    // The oldest key must have been removed
    assertNull(cacheManager.accessCache { it.loadRecord(CacheKey("old"), CacheHeaders.NONE) })
  }
}
