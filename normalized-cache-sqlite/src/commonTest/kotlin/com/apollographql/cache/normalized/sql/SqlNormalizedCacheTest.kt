package com.apollographql.cache.normalized.sql

import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import com.apollographql.apollo.api.json.JsonNumber
import com.apollographql.apollo.exception.apolloExceptionHandler
import com.apollographql.cache.normalized.api.ApolloCacheHeaders
import com.apollographql.cache.normalized.api.CacheHeaders
import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.api.DefaultRecordMerger
import com.apollographql.cache.normalized.api.NormalizedCache
import com.apollographql.cache.normalized.api.Record
import com.apollographql.cache.normalized.api.receivedDate
import com.apollographql.cache.normalized.sql.internal.RecordDatabase
import com.apollographql.cache.normalized.sql.internal.parametersMax
import com.apollographql.cache.normalized.testing.Platform
import com.apollographql.cache.normalized.testing.fieldKey
import com.apollographql.cache.normalized.testing.platform
import com.apollographql.cache.normalized.testing.runTest
import kotlinx.coroutines.flow.toList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqlNormalizedCacheTest {
  private val cache: NormalizedCache = SqlNormalizedCacheFactory().create()

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

  /**
   * A record larger than the 1 MiB blob chunk size is stored as several rows and reassembled on read.
   * The fast path that skips chunking altogether covers everything smaller, so these are the only
   * records that go through the slicing loop.
   */
  @Test
  fun testLargeRecordSpanningSeveralChunks() = runTest(before = { setUp() }, after = { tearDown() }) {
    val record = Record(
        key = STANDARD_KEY,
        fields = mapOf(
            "before" to "value1",
            // Long enough to need three chunks on its own.
            "large" to "a".repeat(2 * BLOB_CHUNK_SIZE + 1),
            "after" to "value2",
        ),
    )
    cache.merge(record = record, cacheHeaders = CacheHeaders.NONE, recordMerger = DefaultRecordMerger)

    val loaded = assertNotNull(cache.loadRecord(STANDARD_KEY, CacheHeaders.NONE))
    assertEquals(record.fields, loaded.fields)
  }

  /**
   * The read path accumulates the chunks of a record in a buffer it reuses for the next one, so a
   * multi-chunk record read alongside others must not spill into them.
   */
  @Test
  fun testLargeRecordLoadedAlongsideOthers() = runTest(before = { setUp() }, after = { tearDown() }) {
    val small1 = Record(key = CacheKey("small1"), fields = mapOf("field" to "value1"))
    val large = Record(key = CacheKey("large"), fields = mapOf("field" to "b".repeat(BLOB_CHUNK_SIZE + 1)))
    val small2 = Record(key = CacheKey("small2"), fields = mapOf("field" to "value2"))
    cache.merge(records = listOf(small1, large, small2), cacheHeaders = CacheHeaders.NONE, recordMerger = DefaultRecordMerger)

    val loaded = cache.loadRecords(listOf(small1.key, large.key, small2.key), CacheHeaders.NONE).associateBy { it.key }
    assertEquals(setOf(small1.key, large.key, small2.key), loaded.keys)
    assertEquals(small1.fields, loaded.getValue(small1.key).fields)
    assertEquals(large.fields, loaded.getValue(large.key).fields)
    assertEquals(small2.fields, loaded.getValue(small2.key).fields)

    val all = cache.loadAllRecords().toList().associateBy { it.key }
    assertEquals(setOf(small1.key, large.key, small2.key), all.keys)
    assertEquals(large.fields, all.getValue(large.key).fields)
  }

  /**
   * Rewriting a record that used to span several chunks must not leave the extra rows behind, or the
   * next read would append them to the new value.
   */
  @Test
  fun testLargeRecordShrinks() = runTest(before = { setUp() }, after = { tearDown() }) {
    cache.merge(
        record = Record(key = STANDARD_KEY, fields = mapOf("field" to "c".repeat(2 * BLOB_CHUNK_SIZE))),
        cacheHeaders = CacheHeaders.NONE,
        recordMerger = DefaultRecordMerger,
    )
    // A different, small value for the same field: the merged record now fits in a single chunk.
    cache.merge(
        record = Record(key = STANDARD_KEY, fields = mapOf("field" to "small")),
        cacheHeaders = CacheHeaders.NONE,
        recordMerger = DefaultRecordMerger,
    )

    val loaded = assertNotNull(cache.loadRecord(STANDARD_KEY, CacheHeaders.NONE))
    assertEquals(mapOf("field" to "small"), loaded.fields)
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
  fun testMetadataKnownKeyAbbreviationOnlyAppliesToInnerMetadataKeys() = runTest(before = { setUp() }, after = { tearDown() }) {
    val fieldNameThatMatchesKnownMetadataKey = ApolloCacheHeaders.RECEIVED_DATE
    val record = Record(
        key = STANDARD_KEY,
        fields = mapOf(
            fieldNameThatMatchesKnownMetadataKey to "value",
            "0" to "otherValue",
        ),
        metadata = mapOf(
            fieldNameThatMatchesKnownMetadataKey to mapOf(
                ApolloCacheHeaders.RECEIVED_DATE to 1L,
                ApolloCacheHeaders.EXPIRATION_DATE to 2L,
            ),
            "0" to mapOf(
                ApolloCacheHeaders.RECEIVED_DATE to 3L,
            ),
        ),
    )

    cache.merge(
        record = record,
        cacheHeaders = CacheHeaders.NONE,
        recordMerger = DefaultRecordMerger,
    )

    val loaded = requireNotNull(cache.loadRecord(STANDARD_KEY, CacheHeaders.NONE))
    assertEquals(record.metadata, loaded.metadata)
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
  fun exceptionCallsExceptionHandler() = runTest(before = { setUp() }, after = { tearDown() }) {
    val badCache = SqlNormalizedCache(RecordDatabase(BadDriver, null))
    var throwable: Throwable? = null
    apolloExceptionHandler = {
      throwable = it
    }

    badCache.loadRecord(STANDARD_KEY, CacheHeaders.NONE)
    assertEquals("Unable to read records from the database", throwable!!.message)
    assertEquals("bad cache", throwable.cause!!.message)

    throwable = null
    badCache.merge(
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
    assertEquals("Unable to merge records into the database", throwable!!.message)
    assertEquals("bad cache", throwable!!.cause!!.message)
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
  fun testCascadeDeleteAcrossMoreKeysThanCanBeBound() = runTest(before = { setUp() }, after = { tearDown() }) {
    // One record referencing more records than can be bound in one statement, so that the cascade walk
    // has to look them up over several of them and carry what it has already visited across the batches.
    // Capped because `parametersMax` is 32766 on recent Android versions, where creating that many
    // records would dwarf the rest of the suite — the batching is exercised on the platforms whose limit
    // is 999.
    val referenceCount = minOf(parametersMax + 1, 1200)
    val referencedKeys = List(referenceCount) { CacheKey("referenced-$it") }
    cache.merge(
        records = referencedKeys.map { key -> Record(key = key, fields = mapOf("field" to "value")) } + Record(
            key = STANDARD_KEY,
            fields = mapOf("references" to referencedKeys),
        ),
        cacheHeaders = CacheHeaders.NONE,
        recordMerger = DefaultRecordMerger,
    )

    assertEquals(referenceCount + 1, cache.remove(cacheKeys = listOf(STANDARD_KEY), cascade = true))
    assertEquals(0, cache.loadAllRecords().toList().size)
  }

  @Test
  fun testLoadAndRemoveSubsetsOfRecords() = runTest(before = { setUp() }, after = { tearDown() }) {
    val keys = List(5) { CacheKey("key-$it") }
    cache.merge(
        records = keys.map { key -> Record(key = key, fields = mapOf("field" to key.key)) },
        cacheHeaders = CacheHeaders.NONE,
        recordMerger = DefaultRecordMerger,
    )

    // 3 keys is not one of the lengths the `IN` list is bound at, so the last one is repeated to fill it:
    // neither the extra parameter nor the duplicate may change what is selected or deleted.
    val subset = keys.take(3)
    assertEquals(subset.toSet(), cache.loadRecords(subset, CacheHeaders.NONE).map { it.key }.toSet())
    assertEquals(3, cache.remove(cacheKeys = subset, cascade = false))
    assertEquals(keys.drop(3), cache.loadAllRecords().toList().map { it.key })
  }

  @Test
  fun testLoadAllRecordsPagesOverAChunkedRecord() = runTest(before = { setUp() }, after = { tearDown() }) {
    // `loadAllRecords` reads a page of rows at a time, and a record of more than a chunk spans several
    // rows: keys sort in the order they are created here, so the large record's first chunk is the last
    // row of the first page and its second chunk the first row of the next one.
    val pageSize = 100
    val largeRecordIndex = pageSize - 1
    val largeField = "a".repeat(1_500_000)
    val records = List(150) { index ->
      Record(
          key = CacheKey("key-${index.toString().padStart(3, '0')}"),
          fields = mapOf("field" to if (index == largeRecordIndex) largeField else "value-$index"),
      )
    }
    cache.merge(records = records, cacheHeaders = CacheHeaders.NONE, recordMerger = DefaultRecordMerger)

    val loaded = cache.loadAllRecords().toList()
    assertEquals(records.map { it.key }, loaded.map { it.key })
    assertEquals(largeField, loaded[largeRecordIndex].fields["field"])
    assertEquals("value-149", loaded.last().fields["field"])
  }

  @Test
  fun testIterateVisitsAllRecords() = runTest(before = { setUp() }, after = { tearDown() }) {
    val keys = List(5) { CacheKey("key-$it") }
    cache.merge(
        records = keys.map { key -> Record(key = key, fields = mapOf("field" to key.key)) },
        cacheHeaders = CacheHeaders.NONE,
        recordMerger = DefaultRecordMerger,
    )

    val visited = mutableListOf<CacheKey>()
    (cache as SqlNormalizedCache).iterate(pageSize = 2) { batch ->
      visited.addAll(batch.map { it.key })
      true
    }
    assertEquals(keys.toSet(), visited.toSet())
  }

  /**
   * Records are delivered `pageSize` at a time, not all at once and not one at a time - the batch
   * sizes are exactly what a caller would rely on to bound the work it does per call.
   */
  @Test
  fun testIterateDeliversPageSizedBatches() = runTest(before = { setUp() }, after = { tearDown() }) {
    val keys = List(250) { CacheKey("key-${it.toString().padStart(3, '0')}") }
    cache.merge(
        records = keys.map { key -> Record(key = key, fields = mapOf("field" to key.key)) },
        cacheHeaders = CacheHeaders.NONE,
        recordMerger = DefaultRecordMerger,
    )

    val batchSizes = mutableListOf<Int>()
    (cache as SqlNormalizedCache).iterate(pageSize = 100) { batch ->
      batchSizes.add(batch.size)
      true
    }
    assertEquals(listOf(100, 100, 50), batchSizes)
  }

  /**
   * `iterate` only hands out batches of records - deciding what to do with them, including deleting
   * some, is entirely up to the caller. Here, the decision needs a field to be deserialized, which is
   * exactly what a `WHERE` clause on the raw blob couldn't express.
   */
  @Test
  fun testIterateThenDeleteMatchingRecords() = runTest(before = { setUp() }, after = { tearDown() }) {
    val keys = List(10) { CacheKey("key-$it") }
    cache.merge(
        records = keys.map { key ->
          val index = key.key.substringAfter("-").toInt()
          Record(key = key, fields = mapOf("parity" to if (index % 2 == 0) "even" else "odd"))
        },
        cacheHeaders = CacheHeaders.NONE,
        recordMerger = DefaultRecordMerger,
    )

    val toDelete = mutableListOf<CacheKey>()
    (cache as SqlNormalizedCache).iterate(pageSize = 3) { batch ->
      toDelete.addAll(batch.filter { it.fields["parity"] == "even" }.map { it.key })
      true
    }
    cache.remove(cacheKeys = toDelete, cascade = false)

    val remainingKeys = cache.loadAllRecords().toList().map { it.key }.toSet()
    val expectedKeys = keys.filter { it.key.substringAfter("-").toInt() % 2 != 0 }.toSet()
    assertEquals(expectedKeys, remainingKeys)
  }

  /**
   * A received date lives in a *field's* metadata, not the record's as a whole: the same record can
   * have one field that was refreshed - and so carries a date - alongside another that was only ever
   * written as part of the initial payload and never got one. That per-field granularity is only
   * visible on a fully deserialized [Record], not something a `WHERE` clause on the raw blob could
   * filter on - so finding fields missing one is exactly what `iterate` is for.
   *
   * Only the stale fields are dropped, the rest of the record is kept. Each page's trimmed records are
   * written as soon as that page is processed, instead of collecting all of them for a single write at
   * the end - so at most one page's worth is ever in memory. The write uses `SKIP_MERGE`, so it
   * overwrites the stored record in place with exactly the trimmed fields, rather than a plain `merge`
   * - which could only add or overwrite fields, never remove one that's absent from the incoming
   * record - and without the extra read of the existing record a normal merge would do first.
   */
  @Test
  fun testIterateDeletesRecordsWithoutAReceivedDate() = runTest(before = { setUp() }, after = { tearDown() }) {
    val withDate = List(3) { CacheKey("withDate-$it") }.map { key ->
      Record(
          key = key,
          fields = mapOf("stable" to key.key, "volatile" to key.key),
          metadata = mapOf(
              "stable" to mapOf(ApolloCacheHeaders.RECEIVED_DATE to 1000L),
              "volatile" to mapOf(ApolloCacheHeaders.RECEIVED_DATE to 1000L),
          ),
      )
    }
    val withoutDate = List(3) { CacheKey("withoutDate-$it") }.map { key ->
      Record(
          key = key,
          fields = mapOf("stable" to key.key, "volatile" to key.key),
          // "stable" still has a received date, "volatile" never got one.
          metadata = mapOf("stable" to mapOf(ApolloCacheHeaders.RECEIVED_DATE to 1000L)),
      )
    }
    cache.merge(records = withDate + withoutDate, cacheHeaders = CacheHeaders.NONE, recordMerger = DefaultRecordMerger)

    val cacheHeaders = CacheHeaders.builder().addHeader(ApolloCacheHeaders.SKIP_MERGE, "true").build()

    (cache as SqlNormalizedCache).iterate(pageSize = 2) { batch ->
      val trimmedRecords = batch.mapNotNull { record ->
        val unknownFields = record.fields.keys.filter { record.receivedDate(it) == null }.toSet()
        if (unknownFields.isEmpty()) null else Record(key = record.key, fields = record.fields - unknownFields, metadata = record.metadata - unknownFields)
      }
      if (trimmedRecords.isNotEmpty()) {
        cache.merge(records = trimmedRecords, cacheHeaders = cacheHeaders, recordMerger = DefaultRecordMerger)
      }
      true
    }

    val loaded = cache.loadRecords((withDate + withoutDate).map { it.key }, CacheHeaders.NONE).associateBy { it.key }
    withDate.forEach { record -> assertEquals(setOf("stable", "volatile"), loaded.getValue(record.key).fields.keys) }
    withoutDate.forEach { record -> assertEquals(setOf("stable"), loaded.getValue(record.key).fields.keys) }
  }

  @Test
  fun testIterateStops() = runTest(before = { setUp() }, after = { tearDown() }) {
    val keys = List(10) { CacheKey("key-${it.toString().padStart(2, '0')}") }
    cache.merge(
        records = keys.map { key -> Record(key = key, fields = mapOf("field" to key.key)) },
        cacheHeaders = CacheHeaders.NONE,
        recordMerger = DefaultRecordMerger,
    )

    val visited = mutableListOf<CacheKey>()
    (cache as SqlNormalizedCache).iterate(pageSize = 1) { batch ->
      if (visited.size == 3) {
        false
      } else {
        visited.addAll(batch.map { it.key })
        true
      }
    }
    assertEquals(3, visited.size)
    // Nothing was deleted, and the scan didn't run to completion.
    assertEquals(keys.toSet(), cache.loadAllRecords().toList().map { it.key }.toSet())
  }

  /**
   * A record spanning several chunk rows is fully assembled into one [Record] before it is handed to a
   * batch, even though reading it advances the cursor onto a different key's rows.
   */
  @Test
  fun testIterateRecordSpanningSeveralChunks() = runTest(before = { setUp() }, after = { tearDown() }) {
    val large = Record(key = CacheKey("large"), fields = mapOf("field" to "a".repeat(2 * BLOB_CHUNK_SIZE + 1)))
    val small = Record(key = CacheKey("small"), fields = mapOf("field" to "value"))
    cache.merge(records = listOf(large, small), cacheHeaders = CacheHeaders.NONE, recordMerger = DefaultRecordMerger)

    val loaded = mutableMapOf<CacheKey, Record>()
    (cache as SqlNormalizedCache).iterate(pageSize = 10) { batch ->
      batch.forEach { loaded[it.key] = it }
      true
    }

    assertEquals(large.fields, loaded.getValue(large.key).fields)
    assertEquals(small.fields, loaded.getValue(small.key).fields)
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

    val normalizedCache = SqlNormalizedCacheFactory().create().apply { clearAll() }
    val sizeOfRecord = normalizedCache.sizeOfRecord(record)
    assertEquals(157, sizeOfRecord)
    normalizedCache.close()
  }

  @Test
  fun cannotReuseNameWithoutClose() = runTest {
    if (platform() == Platform.Js || platform() == Platform.WasmJs) {
      // Wasm and JS don't have file names
      return@runTest
    }

    var exception: Throwable? = null
    apolloExceptionHandler = { exception = it }
    val cache1 = SqlNormalizedCacheFactory().create()
    cache1.clearAll()

    val cache2 = SqlNormalizedCacheFactory().create()
    cache2.clearAll()
    assertEquals("The file apollo.db is already bound to another SqlNormalizedCache. Call SqlNormalizedCache.close() to release it.", exception?.cause?.message)

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
    val cache1 = SqlNormalizedCacheFactory("a.db").create()
    cache1.clearAll()

    val cache2 = SqlNormalizedCacheFactory("b.db").create()
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
    val cache1 = SqlNormalizedCacheFactory().create()
    cache1.clearAll()
    cache1.close()

    val cache2 = SqlNormalizedCacheFactory().create()
    cache2.clearAll()
    assertNull(exception)

    cache2.close()
  }

  private val BadDriver = object : SqlDriver {
    override fun close() {
      throw IllegalStateException("bad cache")
    }

    override fun addListener(vararg queryKeys: String, listener: Query.Listener) {
      throw IllegalStateException("bad cache")
    }

    override fun currentTransaction(): Transacter.Transaction? {
      throw IllegalStateException("bad cache")
    }

    override fun execute(identifier: Int?, sql: String, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<Long> {
      throw IllegalStateException("bad cache")
    }

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> {
      throw IllegalStateException("bad cache")
    }

    override fun newTransaction(): QueryResult<Transacter.Transaction> {
      throw IllegalStateException("bad cache")
    }

    override fun notifyListeners(vararg queryKeys: String) {
      throw IllegalStateException("bad cache")
    }

    override fun removeListener(vararg queryKeys: String, listener: Query.Listener) {
      throw IllegalStateException("bad cache")
    }
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

    /**
     * Mirrors `BLOB_CHUNK_SIZE` in `RecordDatabase`, which is private to it.
     */
    const val BLOB_CHUNK_SIZE = 1024 * 1024
  }
}
