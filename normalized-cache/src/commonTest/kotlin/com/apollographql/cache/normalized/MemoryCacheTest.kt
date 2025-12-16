package com.apollographql.cache.normalized

import com.apollographql.apollo.api.json.JsonNumber
import com.apollographql.cache.normalized.api.ApolloCacheHeaders
import com.apollographql.cache.normalized.api.CacheHeaders
import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.api.DefaultRecordMerger
import com.apollographql.cache.normalized.api.NormalizedCache
import com.apollographql.cache.normalized.api.Record
import com.apollographql.cache.normalized.memory.MemoryCache
import com.apollographql.cache.normalized.memory.MemoryCacheFactory
import com.apollographql.cache.normalized.testing.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryCacheTest {
  @Test
  fun testSaveAndLoad_singleRecord() = runTest {
    val lruCache = createCache()
    val testRecord = createTestRecord("1")
    lruCache.merge(testRecord, CacheHeaders.NONE, DefaultRecordMerger)

    assertTestRecordPresentAndAccurate(testRecord, lruCache)
  }

  @Test
  fun testSaveAndLoad_multipleRecord_readSingle() = runTest {
    val lruCache = createCache()
    val testRecord1 = createTestRecord("1")
    val testRecord2 = createTestRecord("2")
    val testRecord3 = createTestRecord("3")
    val records = listOf(testRecord1, testRecord2, testRecord3)
    lruCache.merge(records, CacheHeaders.NONE, DefaultRecordMerger)

    assertTestRecordPresentAndAccurate(testRecord1, lruCache)
    assertTestRecordPresentAndAccurate(testRecord2, lruCache)
    assertTestRecordPresentAndAccurate(testRecord3, lruCache)
  }

  @Test
  fun testSaveAndLoad_multipleRecord_readMultiple() = runTest {
    val lruCache = createCache()
    val testRecord1 = createTestRecord("1")
    val testRecord2 = createTestRecord("2")
    val testRecord3 = createTestRecord("3")
    val records = listOf(testRecord1, testRecord2, testRecord3)
    lruCache.merge(records, CacheHeaders.NONE, DefaultRecordMerger)

    val readRecords = lruCache.loadRecords(listOf(CacheKey("key1"), CacheKey("key2"), CacheKey("key3")), CacheHeaders.NONE)
    assertTrue(readRecords.containsAll(records))
  }

  @Test
  fun testLoad_recordNotPresent() = runTest {
    val lruCache = createCache()
    val record = lruCache.loadRecord(CacheKey("key1"), CacheHeaders.NONE)
    assertNull(record)
  }

  @Test
  fun testEviction() = runTest {
    val testRecord1 = createTestRecord("1")
    val testRecord2 = createTestRecord("2")
    val testRecord3 = createTestRecord("3")

    val lruCache = createCache(
        // all records won't fit as there is timestamp that stored with each record
        maxSizeBytes = 600
    )

    val records = listOf(testRecord1, testRecord2, testRecord3)
    lruCache.merge(records, CacheHeaders.NONE, DefaultRecordMerger)

    //Cache does not reveal exactly how it handles eviction, but appears
    //to evict more than is strictly necessary. Regardless, any sane eviction
    //strategy should leave the third record in this test case, and evict the first record.
    assertNull(lruCache.loadRecord(testRecord1.key, CacheHeaders.NONE))
    assertNotNull(lruCache.loadRecord(testRecord3.key, CacheHeaders.NONE))
  }

  @Test
  fun testEviction_recordChange() = runTest {
    val testRecord1 = createTestRecord("1")
    val testRecord2 = createTestRecord("2")
    val testRecord3 = createTestRecord("3")

    val lruCache = createCache(
        maxSizeBytes = 800
    )

    val records = listOf(testRecord1, testRecord2, testRecord3)
    lruCache.merge(records, CacheHeaders.NONE, DefaultRecordMerger)

    assertNotNull(lruCache.loadRecord(testRecord1.key, CacheHeaders.NONE))
    assertNotNull(lruCache.loadRecord(testRecord2.key, CacheHeaders.NONE))
    assertNotNull(lruCache.loadRecord(testRecord3.key, CacheHeaders.NONE))

    val updatedRestRecord1 = Record(
        fields = testRecord1.fields.plus("field3" to "value3"),
        key = testRecord1.key,
        mutationId = testRecord1.mutationId
    )

    lruCache.merge(updatedRestRecord1, CacheHeaders.NONE, DefaultRecordMerger)

    assertNotNull(lruCache.loadRecord(testRecord1.key, CacheHeaders.NONE))
    assertNotNull(lruCache.loadRecord(testRecord2.key, CacheHeaders.NONE))
    assertNotNull(lruCache.loadRecord(testRecord3.key, CacheHeaders.NONE))
  }

  @Test
  fun testExpiresImmediately() = runTest {
    val testRecord = createTestRecord("")
    val lruCache = createCache(expireAfterMillis = 0)
    lruCache.merge(testRecord, CacheHeaders.NONE, DefaultRecordMerger)

    assertNull(lruCache.loadRecord(testRecord.key, CacheHeaders.NONE))
  }


  @Test
  fun testDualCacheSingleRecord() = runTest {
    val secondaryCache = createCache()
    val primaryCache = createCache(nextCache = secondaryCache)

    val mockRecord = createTestRecord("")
    primaryCache.merge(mockRecord, CacheHeaders.NONE, DefaultRecordMerger)

    //verify write through behavior
    assertEquals(mockRecord.fields, primaryCache.loadRecord(mockRecord.key, CacheHeaders.NONE)?.fields)
    assertEquals(mockRecord.fields, secondaryCache.loadRecord(mockRecord.key, CacheHeaders.NONE)?.fields)
  }

  @Test
  fun testDualCacheMultipleRecord() = runTest {
    val secondaryCache = createCache()
    val primaryCache = createCache(nextCache = secondaryCache)

    val testRecord1 = createTestRecord("1")
    val testRecord2 = createTestRecord("2")
    val testRecord3 = createTestRecord("3")
    val records = listOf(testRecord1, testRecord2, testRecord3)
    primaryCache.merge(records, CacheHeaders.NONE, DefaultRecordMerger)

    val keys = listOf(testRecord1.key, testRecord2.key, testRecord3.key)
    assertEquals(3, primaryCache.loadRecords(keys, CacheHeaders.NONE).size)
    assertEquals(3, secondaryCache.loadRecords(keys, CacheHeaders.NONE).size)
  }

  @Test
  fun testDualCache_recordNotPresent() = runTest {
    val secondaryCache = createCache()
    val primaryCache = createCache(nextCache = secondaryCache)
    assertNull(primaryCache.loadRecord(CacheKey("key"), CacheHeaders.NONE))
  }


  @Test
  fun testDualCache_clearAll() = runTest {
    val secondaryCache = createCache()
    val primaryCache = createCache(nextCache = secondaryCache)

    val testRecord1 = createTestRecord("1")
    val testRecord2 = createTestRecord("2")
    val testRecord3 = createTestRecord("3")
    val records = listOf(testRecord1, testRecord2, testRecord3)
    primaryCache.merge(records, CacheHeaders.NONE, DefaultRecordMerger)

    primaryCache.clearAll()

    assertEquals(0, primaryCache.size())
    assertEquals(0, secondaryCache.size())
  }

  @Test
  fun testDualCache_readFromNext() = runTest {
    val secondaryCache = createCache()
    val primaryCache = createCache(nextCache = secondaryCache)

    val testRecord = createTestRecord("")
    primaryCache.merge(testRecord, CacheHeaders.NONE, DefaultRecordMerger)

    primaryCache.clearCurrentCache()

    assertEquals(testRecord.fields, primaryCache.loadRecord(testRecord.key, CacheHeaders.NONE)?.fields)
  }

  @Test
  fun testHeader_noCache() = runTest {
    val lruCache = createCache()
    val testRecord = createTestRecord("1")

    val headers = CacheHeaders.builder().addHeader(ApolloCacheHeaders.DO_NOT_STORE, "true").build()

    lruCache.merge(testRecord, headers, DefaultRecordMerger)

    assertNull(lruCache.loadRecord(testRecord.key, headers))
  }

  @Test
  fun testDump() = runTest {
    val lruCache = createCache()

    val testRecord1 = createTestRecord("1")
    val testRecord2 = createTestRecord("2")
    val testRecord3 = createTestRecord("3")
    val records = listOf(testRecord1, testRecord2, testRecord3)
    lruCache.merge(records, CacheHeaders.NONE, DefaultRecordMerger)

    with(lruCache.dump()) {
      val cache = this[MemoryCache::class]!!

      assertTrue(cache.keys.containsAll(records.map { it.key }))
      assertEquals(testRecord1, cache[testRecord1.key])
      assertEquals(testRecord2, cache[testRecord2.key])
      assertEquals(testRecord3, cache[testRecord3.key])
    }
  }

  private fun assertEquals(expected: Record?, actual: Record?) {
    assertTrue((expected == null) == (actual == null))
    if (expected == null || actual == null) {
      return
    }
    assertEquals(expected.key, actual.key)
    assertEquals(expected.fields, actual.fields)
    assertEquals(expected.mutationId, actual.mutationId)
    assertEquals(expected.metadata, actual.metadata)
  }


  @Test
  fun testRemove_cascadeFalse() = runTest {
    val lruCache = createCache()

    val record1 = Record(
        key = CacheKey("id_1"),
        fields = mapOf(
            "a" to "stringValueA",
            "b" to "stringValueB"
        )
    )

    val record2 = Record(
        key = CacheKey("id_2"),
        fields = mapOf(
            "a" to CacheKey("id_1"),
        )
    )

    val records = listOf(record1, record2)
    lruCache.merge(records, CacheHeaders.NONE, DefaultRecordMerger)

    assertTrue(lruCache.remove(record2.key, cascade = false))
    assertNotNull(lruCache.loadRecord(record1.key, CacheHeaders.NONE))
  }

  @Test
  fun testRemove_cascadeTrue() = runTest {
    val lruCache = createCache()

    val record1 = Record(
        key = CacheKey("id_1"),
        fields = mapOf(
            "a" to "stringValueA",
            "b" to "stringValueB"
        )
    )

    val record2 = Record(
        key = CacheKey("id_2"),
        fields = mapOf(
            "a" to CacheKey("id_1"),
        )
    )

    val records = listOf(record1, record2)
    lruCache.merge(records, CacheHeaders.NONE, DefaultRecordMerger)

    assertTrue(lruCache.remove(record2.key, cascade = true))
    assertNull(lruCache.loadRecord(record1.key, CacheHeaders.NONE))
  }

  private fun createCache(
      maxSizeBytes: Int = 10 * 1024,
      expireAfterMillis: Long = -1,
      nextCache: NormalizedCache? = null,
  ): MemoryCache {
    return MemoryCache(maxSizeBytes = maxSizeBytes, expireAfterMillis = expireAfterMillis, nextCache = nextCache)
  }

  private suspend fun assertTestRecordPresentAndAccurate(testRecord: Record, cache: NormalizedCache) {
    val cacheRecord = checkNotNull(cache.loadRecord(testRecord.key, CacheHeaders.NONE))
    assertEquals(testRecord.key, cacheRecord.key)
    assertEquals(testRecord.fields, cacheRecord.fields)
  }

  private fun createTestRecord(id: String): Record {
    return Record(
        key = CacheKey("key$id"),
        fields = mapOf(
            "field1" to "stringValueA$id",
            "field2" to "stringValueB$id"
        )
    )
  }

  @Test
  fun testRecordWeigher() {
    val expectedDouble = 1.23
    val expectedLongValue = Long.MAX_VALUE
    val expectedStringValue = "StringValue"
    val expectedBooleanValue = true
    val expectedNumberValue = JsonNumber("10")
    val expectedCacheKey = CacheKey("foo")
    val expectedCacheKeyList = listOf(CacheKey("bar"), CacheKey("baz"))
    val expectedScalarList = listOf("scalarOne", "scalarTwo")
    val record = Record(
        key = CacheKey("root"),
        fields = mapOf(
            "double" to expectedDouble,
            "string" to expectedStringValue,
            "boolean" to expectedBooleanValue,
            "long" to expectedLongValue,
            "number" to expectedNumberValue,
            "cacheReference" to expectedCacheKey,
            "scalarList" to expectedScalarList,
            "referenceList" to expectedCacheKeyList,
        ),
    )

    val normalizedCache = MemoryCacheFactory().create()
    val sizeOfRecord = normalizedCache.sizeOfRecord(record)
    assertEquals(262, sizeOfRecord)
  }
}
