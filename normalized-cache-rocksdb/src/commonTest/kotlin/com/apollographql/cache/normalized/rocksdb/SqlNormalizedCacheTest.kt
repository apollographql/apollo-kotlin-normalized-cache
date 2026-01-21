package com.apollographql.cache.normalized.rocksdb

import com.apollographql.apollo.api.json.JsonNumber
import com.apollographql.apollo.exception.apolloExceptionHandler
import com.apollographql.cache.normalized.api.ApolloCacheHeaders
import com.apollographql.cache.normalized.api.CacheHeaders
import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.api.DefaultRecordMerger
import com.apollographql.cache.normalized.api.NormalizedCache
import com.apollographql.cache.normalized.api.Record
import com.apollographql.cache.normalized.testing.Platform
import com.apollographql.cache.normalized.testing.fieldKey
import com.apollographql.cache.normalized.testing.platform
import com.apollographql.cache.normalized.testing.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqlNormalizedCacheTest {
  private val cache: NormalizedCache = RocksDBNormalizedCacheFactory().create()

  suspend fun setUp() {
    cache.clearAll()
  }

  suspend fun tearDown() {
    cache.close()
  }

  @Test
  fun testRecordCreation() = runTest(before = { setUp() }, after = { tearDown() }) {
    createRecord(STANDARD_KEY)
    assertNotNull(cache.loadRecord(STANDARD_KEY, CacheHeaders.NONE))
  }

  @Test
  fun testRecordCreation_root() = runTest(before = { setUp() }, after = { tearDown() }) {
    createRecord(QUERY_ROOT_KEY)
    assertNotNull(cache.loadRecord(QUERY_ROOT_KEY, CacheHeaders.NONE))
  }

  @Test
  fun testRecordSelection() = runTest(before = { setUp() }, after = { tearDown() }) {
    createRecord(STANDARD_KEY)
    val record = cache.loadRecord(STANDARD_KEY, CacheHeaders.NONE)
    assertNotNull(record)
    assertEquals(expected = STANDARD_KEY, actual = record.key)
  }

  @Test
  fun testMultipleRecordSelection() = runTest(before = { setUp() }, after = { tearDown() }) {
    createRecord(STANDARD_KEY)
    createRecord(QUERY_ROOT_KEY)
    val selectionKeys = setOf(STANDARD_KEY, QUERY_ROOT_KEY)
    val records = cache.loadRecords(selectionKeys, CacheHeaders.NONE)
    val selectedKeys = records.map { it.key }.toSet()
    assertEquals(selectionKeys, selectedKeys)
  }

  @Test
  fun testRecordSelection_root() = runTest(before = { setUp() }, after = { tearDown() }) {
    createRecord(QUERY_ROOT_KEY)
    val record = requireNotNull(cache.loadRecord(QUERY_ROOT_KEY, CacheHeaders.NONE))
    assertNotNull(record)
    assertEquals(expected = QUERY_ROOT_KEY, actual = record.key)
  }

  @Test
  fun testRecordSelection_recordNotPresent() = runTest(before = { setUp() }, after = { tearDown() }) {
    val record = cache.loadRecord(STANDARD_KEY, CacheHeaders.NONE)
    assertNull(record)
  }

  @Test
  fun testRecordMerge() = runTest(before = { setUp() }, after = { tearDown() }) {
    cache.merge(
        record = Record(
            key = STANDARD_KEY,
            fields = mapOf(
                "fieldKey" to "valueUpdated",
                "newFieldKey" to true,
            ),
        ),
        cacheHeaders = CacheHeaders.NONE,
        recordMerger = DefaultRecordMerger,
    )
    val record = cache.loadRecord(STANDARD_KEY, CacheHeaders.NONE)
    assertNotNull(record)
    assertEquals(expected = "valueUpdated", actual = record.fields["fieldKey"])
    assertEquals(expected = true, actual = record.fields["newFieldKey"])
  }

  @Test
  fun testRecordDelete() = runTest(before = { setUp() }, after = { tearDown() }) {
    createRecord(STANDARD_KEY)
    cache.merge(
        record = Record(
            key = STANDARD_KEY,
            fields = mapOf(
                "fieldKey" to "valueUpdated",
                "newFieldKey" to true,
            ),
        ),
        cacheHeaders = CacheHeaders.NONE,
        recordMerger = DefaultRecordMerger,
    )
    cache.remove(cacheKey = STANDARD_KEY, cascade = false)
    val record = cache.loadRecord(STANDARD_KEY, CacheHeaders.NONE)
    assertNull(record)
  }

  @Test
  fun testClearAll() = runTest(before = { setUp() }, after = { tearDown() }) {
    createRecord(QUERY_ROOT_KEY)
    createRecord(STANDARD_KEY)
    cache.clearAll()
    assertNull(cache.loadRecord(QUERY_ROOT_KEY, CacheHeaders.NONE))
    assertNull(cache.loadRecord(STANDARD_KEY, CacheHeaders.NONE))
  }

  @Test
  fun testHeader_noCache() = runTest(before = { setUp() }, after = { tearDown() }) {
    cache.merge(
        record = Record(
            key = STANDARD_KEY,
            fields = emptyMap(),
        ),
        cacheHeaders = CacheHeaders.builder().addHeader(ApolloCacheHeaders.DO_NOT_STORE, "true").build(),
        recordMerger = DefaultRecordMerger,
    )
    val record = cache.loadRecord(STANDARD_KEY, CacheHeaders.NONE)
    assertNull(record)
  }

  @Test
  fun testRecordMerge_noOldRecord() = runTest(before = { setUp() }, after = { tearDown() }) {
    val changedKeys = cache.merge(
        record = Record(
            key = STANDARD_KEY,
            fields = mapOf(
                "fieldKey" to "valueUpdated",
                "newFieldKey" to true,
            ),
        ),
        cacheHeaders = CacheHeaders.NONE,
        recordMerger = DefaultRecordMerger,
    )
    val record = cache.loadRecord(STANDARD_KEY, CacheHeaders.NONE)
    assertNotNull(record)
    assertEquals(expected = setOf(STANDARD_KEY.fieldKey("fieldKey"), STANDARD_KEY.fieldKey("newFieldKey")), actual = changedKeys)
    assertEquals(expected = "valueUpdated", actual = record.fields["fieldKey"])
    assertEquals(expected = true, actual = record.fields["newFieldKey"])
  }

  @Test
  fun testRecordMerge_withOldRecord() = runTest(before = { setUp() }, after = { tearDown() }) {
    createRecord(STANDARD_KEY)
    cache.merge(
        record = Record(
            key = STANDARD_KEY,
            fields = mapOf(
                "fieldKey" to "valueUpdated",
                "newFieldKey" to true,
            ),
        ),
        cacheHeaders = CacheHeaders.NONE,
        recordMerger = DefaultRecordMerger,
    )
    val record = cache.loadRecord(STANDARD_KEY, CacheHeaders.NONE)
    assertNotNull(record)
    assertEquals(expected = "valueUpdated", actual = record.fields["fieldKey"])
    assertEquals(expected = true, actual = record.fields["newFieldKey"])
  }

  @Test
  fun testCascadeDeleteWithSelfReference() = runTest(before = { setUp() }, after = { tearDown() }) {
    // Creating a self-referencing record
    cache.merge(
        record = Record(
            key = CacheKey("selfRefKey"),
            fields = mapOf(
                "field1" to "value1",
                "selfRef" to CacheKey("selfRefKey"),
            ),
        ),
        cacheHeaders = CacheHeaders.NONE,
        recordMerger = DefaultRecordMerger,
    )

    val result = cache.remove(cacheKey = CacheKey("selfRefKey"), cascade = true)

    assertTrue(result)
    val record = cache.loadRecord(CacheKey("selfRefKey"), CacheHeaders.NONE)
    assertNull(record)
  }

  @Test
  fun testCascadeDeleteWithCyclicReferences() = runTest(before = { setUp() }, after = { tearDown() }) {
    // Creating two records that reference each other
    cache.merge(
        record = Record(
            key = CacheKey("key1"),
            fields = mapOf(
                "field1" to "value1",
                "refToKey2" to CacheKey("key2"),
            ),
        ),
        cacheHeaders = CacheHeaders.NONE,
        recordMerger = DefaultRecordMerger,
    )

    cache.merge(
        record = Record(
            key = CacheKey("key2"),
            fields = mapOf(
                "field1" to "value2",
                "refToKey1" to CacheKey("key1"),
            ),
        ),
        cacheHeaders = CacheHeaders.NONE,
        recordMerger = DefaultRecordMerger,
    )

    val result = cache.remove(cacheKey = CacheKey("key1"), cascade = true)

    assertTrue(result)
    assertNull(cache.loadRecord(CacheKey("key1"), CacheHeaders.NONE))
    assertNull(cache.loadRecord(CacheKey("key2"), CacheHeaders.NONE))
  }

  @Test
  fun testSizeOfRecord() = runTest {
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

    val normalizedCache = RocksDBNormalizedCacheFactory().create().apply { clearAll() }
    val sizeOfRecord = normalizedCache.sizeOfRecord(record)
    assertEquals(157, sizeOfRecord)
  }

  @Test
  fun cannotReuseNameWithoutClose() = runTest {
    if (platform() == Platform.Js || platform() == Platform.WasmJs) {
      // Wasm and JS don't have file names
      return@runTest
    }

    var exception: Throwable? = null
    apolloExceptionHandler = { exception = it }
    val cache1 = RocksDBNormalizedCacheFactory().create()
    cache1.clearAll()

    val cache2 = RocksDBNormalizedCacheFactory().create()
    cache2.clearAll()
    assertEquals("The file apollo.db is already bound to another RocksDBNormalizedCache. Call RocksDBNormalizedCache.close() to release it.", exception?.cause?.message)

    cache1.close()
  }

  @Test
  fun canUseDifferentNames() = runTest {
    if (platform() == Platform.Js || platform() == Platform.WasmJs) {
      // Wasm and JS don't have file names
      return@runTest
    }

    var exception: Throwable? = null
    apolloExceptionHandler = { exception = it }
    val cache1 = RocksDBNormalizedCacheFactory("a.db").create()
    cache1.clearAll()

    val cache2 = RocksDBNormalizedCacheFactory("b.db").create()
    cache2.clearAll()
    assertNull(exception)
  }

  @Test
  fun canReuseNameAfterClose() = runTest {
    if (platform() == Platform.Js || platform() == Platform.WasmJs) {
      // Wasm and JS don't have file names
      return@runTest
    }

    var exception: Throwable? = null
    apolloExceptionHandler = { exception = it }
    val cache1 = RocksDBNormalizedCacheFactory().create()
    cache1.clearAll()
    cache1.close()

    val cache2 = RocksDBNormalizedCacheFactory().create()
    cache2.clearAll()
    assertNull(exception)

    cache2.close()
  }

  private suspend fun createRecord(key: CacheKey) {
    cache.merge(
        record = Record(
            key = key,
            fields = mapOf(
                "field1" to "value1",
                "field2" to "value2",
            ),
        ),
        cacheHeaders = CacheHeaders.NONE,
        recordMerger = DefaultRecordMerger,
    )
  }

  companion object {
    val STANDARD_KEY = CacheKey("key")
    val QUERY_ROOT_KEY = CacheKey.QUERY_ROOT
  }
}
