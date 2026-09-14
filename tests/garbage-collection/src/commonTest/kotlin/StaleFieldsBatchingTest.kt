package test

import com.apollographql.cache.normalized.api.ApolloCacheHeaders
import com.apollographql.cache.normalized.api.CacheHeaders
import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.api.MaxAge
import com.apollographql.cache.normalized.api.NormalizedCache
import com.apollographql.cache.normalized.api.Record
import com.apollographql.cache.normalized.api.RecordMerger
import com.apollographql.cache.normalized.api.RecordMergerContext
import com.apollographql.cache.normalized.api.SchemaCoordinatesMaxAgeProvider
import com.apollographql.cache.normalized.removeStaleFields
import com.apollographql.cache.normalized.testing.fieldKey
import com.apollographql.cache.normalized.testing.runTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class StaleFieldsBatchingTest {
  private val now = 1_000_000_000L

  private val recordA = Record(
      key = CacheKey("A"),
      fields = mapOf("__typename" to "TypeA", "refField" to CacheKey("B")),
      metadata = mapOf("refField" to mapOf(ApolloCacheHeaders.RECEIVED_DATE to now - 50)),
  )

  private val recordB = Record(
      key = CacheKey("B"),
      fields = mapOf("__typename" to "TypeB", "field1" to "value1"),
      metadata = mapOf("field1" to mapOf(ApolloCacheHeaders.RECEIVED_DATE to now - 1)),
  )

  // Fresh for a long time if the field's type resolves to "TypeB", immediately stale for any other (known) type.
  private val maxAgeProvider = SchemaCoordinatesMaxAgeProvider(
      mapOf("TypeB" to MaxAge.Duration(1_000.seconds)),
      defaultMaxAge = Duration.ZERO,
  )

  // With a batchSize of 1, B is flushed and removed from the cache as soon as it's processed, before A is looked at.
  // When A is processed, the guessType() lookup for B fails: refField is dangling a dangling reference, so
  // it's removed - leaving A empty, so A is removed too.
  @Test
  fun smallBatchSizeRemovesAFieldThatBecameDanglingInAnEarlierBatch() = runTest {
    val cache = FakeNormalizedCache(listOf(recordB, recordA))
    val result = cache.removeStaleFields(maxAgeProvider = maxAgeProvider, batchSize = 1, clock = { now * 1000 })

    assertEquals(
        setOf(CacheKey("B").fieldKey("field1"), CacheKey("A").fieldKey("refField")),
        result.removedFields,
    )
    assertEquals(setOf(CacheKey("B"), CacheKey("A")), result.removedRecords)
  }

  // With a large enough batchSize, nothing is flushed until all records have been processed.
  // When A is processed, B is still present, so refField's guessType() correctly resolves to "TypeB" and it's kept because it isn't stale.
  @Test
  fun largeBatchSizeKeepsAFieldWhoseReferenceIsStillPresent() = runTest {
    val cache = FakeNormalizedCache(listOf(recordB, recordA))
    val result = cache.removeStaleFields(maxAgeProvider = maxAgeProvider, batchSize = 100, clock = { now * 1000 })

    assertEquals(setOf(CacheKey("B").fieldKey("field1")), result.removedFields)
    assertEquals(setOf(CacheKey("B")), result.removedRecords)
  }
}

/**
 * Fake in-memory [NormalizedCache] that conserves order.
 */
private class FakeNormalizedCache(initialRecords: List<Record>) : NormalizedCache {
  private val records = LinkedHashMap<CacheKey, Record>().apply { for (record in initialRecords) put(record.key, record) }

  override suspend fun loadRecord(key: CacheKey, cacheHeaders: CacheHeaders): Record? = records[key]

  override suspend fun loadRecords(keys: Collection<CacheKey>, cacheHeaders: CacheHeaders): Collection<Record> {
    return keys.mapNotNull { records[it] }
  }

  override suspend fun loadAllRecords(batchSize: Int): Flow<Record> {
    return buildList { for ((_, record) in records) add(record) }.asFlow()
  }

  override suspend fun dump(): Map<KClass<*>, Map<CacheKey, Record>> = mapOf(this::class to records.toMap())

  override suspend fun merge(record: Record, cacheHeaders: CacheHeaders, recordMerger: RecordMerger) =
    merge(records = listOf(record), cacheHeaders = cacheHeaders, recordMerger = recordMerger)

  override suspend fun merge(records: Collection<Record>, cacheHeaders: CacheHeaders, recordMerger: RecordMerger): Set<String> {
    for (record in records) {
      val existingRecord = this.records[record.key]
      if (existingRecord != null) {
        val (mergedRecord, _) = recordMerger.merge(RecordMergerContext(existing = existingRecord, incoming = record, cacheHeaders = cacheHeaders))
        this.records[record.key] = mergedRecord
      } else {
        this.records[record.key] = record
      }
    }
    return emptySet()
  }

  override suspend fun clearAll() {
    records.clear()
  }

  override suspend fun remove(cacheKey: CacheKey, cascade: Boolean): Boolean {
    return records.remove(cacheKey) != null
  }

  override suspend fun remove(cacheKeys: Collection<CacheKey>, cascade: Boolean): Int {
    var count = 0
    for (key in cacheKeys) {
      if (records.remove(key) != null) count++
    }
    return count
  }
}
