package com.apollographql.cache.normalized

import com.apollographql.apollo.api.Error
import com.apollographql.cache.normalized.api.ApolloCacheHeaders
import com.apollographql.cache.normalized.api.CacheHeaders
import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.api.DefaultRecordMerger
import com.apollographql.cache.normalized.api.FieldRecordMerger
import com.apollographql.cache.normalized.api.Record
import com.apollographql.cache.normalized.api.RecordMergerContext
import com.apollographql.cache.normalized.api.fieldKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class DefaultRecordMergerTest {
  @Test
  fun mergeMetaData() {
    val existing = Record(
        key = CacheKey("key"),
        fields = mapOf(
            "field1" to "value1",
            "field2" to "value2"
        ),
        mutationId = null,
        metadata = mapOf(
            "field1" to mapOf(
                "field1meta1" to "field1metaValue1",
                "field1meta2" to "field1metaValue2",
            ),
            "field2" to mapOf(
                "field2meta1" to "field2metaValue1",
                "field2meta2" to "field2metaValue2",
            ),
        ),
    )

    val incoming = Record(
        key = CacheKey("key"),
        fields = mapOf(
            "field1" to "value1.incoming",
            "field3" to "value3",
        ),
        mutationId = null,
        metadata = mapOf(
            "field1" to mapOf(
                "field1meta1" to "field1metaValue1.incoming",
                "field1meta3" to "field1metaValue3",
            ),
            "field3" to mapOf(
                "field3meta1" to "field3metaValue1",
                "field3meta2" to "field3metaValue2",
            ),
        ),
    )

    val mergedRecord = DefaultRecordMerger.merge(RecordMergerContext(existing, incoming, CacheHeaders.NONE)).first

    val expected = Record(
        key = CacheKey("key"),
        fields = mapOf(
            "field1" to "value1.incoming",
            "field2" to "value2",
            "field3" to "value3",
        ),
        mutationId = null,
        metadata = mapOf(
            "field1" to mapOf(
                "field1meta1" to "field1metaValue1.incoming",
                "field1meta2" to "field1metaValue2",
                "field1meta3" to "field1metaValue3",
            ),
            "field2" to mapOf(
                "field2meta1" to "field2metaValue1",
                "field2meta2" to "field2metaValue2",
            ),
            "field3" to mapOf(
                "field3meta1" to "field3metaValue1",
                "field3meta2" to "field3metaValue2",
            ),
        ),
    )

    assertEquals(expected.fields, mergedRecord.fields)
    assertEquals(expected.metadata, mergedRecord.metadata)
  }

  @Test
  fun mergeNoMetadata() {
    val existing = Record(key = CacheKey("key"), fields = mapOf("field1" to "value1"))
    val incoming = Record(key = CacheKey("key"), fields = mapOf("field2" to "value2"))

    val mergedRecord = DefaultRecordMerger.merge(RecordMergerContext(existing, incoming, CacheHeaders.NONE)).first

    assertEquals(mapOf("field1" to "value1", "field2" to "value2"), mergedRecord.fields)
    assertEquals(emptyMap(), mergedRecord.metadata)
  }

  @Test
  fun mergeMetadataOnOneSideOnly() {
    val metadata = mapOf("field1" to mapOf("meta1" to "metaValue1"))

    val existingHasIt = DefaultRecordMerger.merge(
        RecordMergerContext(
            existing = Record(key = CacheKey("key"), fields = mapOf("field1" to "value1"), metadata = metadata),
            incoming = Record(key = CacheKey("key"), fields = mapOf("field2" to "value2")),
            cacheHeaders = CacheHeaders.NONE,
        ),
    ).first
    assertEquals(metadata, existingHasIt.metadata)

    val incomingHasIt = DefaultRecordMerger.merge(
        RecordMergerContext(
            existing = Record(key = CacheKey("key"), fields = mapOf("field2" to "value2")),
            incoming = Record(key = CacheKey("key"), fields = mapOf("field1" to "value1"), metadata = metadata),
            cacheHeaders = CacheHeaders.NONE,
        ),
    ).first
    assertEquals(metadata, incomingHasIt.metadata)
  }

  /**
   * A cached `null` is a value like any other: an incoming error must not replace it unless
   * [ApolloCacheHeaders.ERRORS_REPLACE_CACHED_VALUES] says so. `null` is also the value a lookup
   * returns for an absent field, which is what makes this worth pinning down.
   */
  @Test
  fun incomingErrorDoesNotReplaceCachedNull() {
    val existing = Record(key = CacheKey("key"), fields = mapOf("field1" to null))
    val incoming = Record(key = CacheKey("key"), fields = mapOf("field1" to Error.Builder("boom").build()))

    val (mergedRecord, changedKeys) = DefaultRecordMerger.merge(RecordMergerContext(existing, incoming, CacheHeaders.NONE))

    assertEquals(mapOf("field1" to null), mergedRecord.fields)
    assertEquals(emptySet(), changedKeys)
  }

  @Test
  fun incomingErrorReplacesCachedNullWhenHeaderIsSet() {
    val error = Error.Builder("boom").build()
    val existing = Record(key = CacheKey("key"), fields = mapOf("field1" to null))
    val incoming = Record(key = CacheKey("key"), fields = mapOf("field1" to error))

    val (mergedRecord, changedKeys) = DefaultRecordMerger.merge(
        RecordMergerContext(
            existing = existing,
            incoming = incoming,
            cacheHeaders = CacheHeaders.builder().addHeader(ApolloCacheHeaders.ERRORS_REPLACE_CACHED_VALUES, "true").build(),
        ),
    )

    assertEquals(mapOf("field1" to error), mergedRecord.fields)
    assertEquals(setOf(CacheKey("key").fieldKey("field1")), changedKeys)
  }

  /**
   * Nothing is cached for the field, so the incoming error is what the cache learns about it.
   */
  @Test
  fun incomingErrorIsStoredForAnAbsentField() {
    val error = Error.Builder("boom").build()
    val existing = Record(key = CacheKey("key"), fields = emptyMap())
    val incoming = Record(key = CacheKey("key"), fields = mapOf("field1" to error))

    val (mergedRecord, changedKeys) = DefaultRecordMerger.merge(RecordMergerContext(existing, incoming, CacheHeaders.NONE))

    assertEquals(mapOf("field1" to error), mergedRecord.fields)
    assertEquals(setOf(CacheKey("key").fieldKey("field1")), changedKeys)
  }

  @Test
  fun mergingNullOverNullDoesNotReportAChange() {
    val existing = Record(key = CacheKey("key"), fields = mapOf("field1" to null))
    val incoming = Record(key = CacheKey("key"), fields = mapOf("field1" to null))

    val (mergedRecord, changedKeys) = DefaultRecordMerger.merge(RecordMergerContext(existing, incoming, CacheHeaders.NONE))

    assertEquals(mapOf("field1" to null), mergedRecord.fields)
    assertEquals(emptySet(), changedKeys)
  }

  /**
   * An incoming `null` for a field that was absent is new information, so it is a change even though
   * both a lookup of the existing field and the incoming value are `null`.
   */
  @Test
  fun mergingNullOverAnAbsentFieldReportsAChange() {
    val existing = Record(key = CacheKey("key"), fields = emptyMap())
    val incoming = Record(key = CacheKey("key"), fields = mapOf("field1" to null))

    val (mergedRecord, changedKeys) = DefaultRecordMerger.merge(RecordMergerContext(existing, incoming, CacheHeaders.NONE))

    assertEquals(mapOf("field1" to null), mergedRecord.fields)
    assertEquals(setOf(CacheKey("key").fieldKey("field1")), changedKeys)
  }

  /**
   * [FieldRecordMerger] separates "absent" from "present" to decide whether to call its [FieldRecordMerger.FieldMerger]
   * at all, so it gets the same treatment.
   */
  @Test
  fun fieldRecordMergerIncomingErrorDoesNotReplaceCachedNull() {
    var mergerCalled = false
    val merger = FieldRecordMerger(
        object : FieldRecordMerger.FieldMerger {
          override fun mergeFields(
              existing: FieldRecordMerger.FieldInfo,
              incoming: FieldRecordMerger.FieldInfo,
          ): FieldRecordMerger.FieldInfo {
            mergerCalled = true
            return incoming
          }
        },
    )
    val existing = Record(key = CacheKey("key"), fields = mapOf("field1" to null))
    val incoming = Record(key = CacheKey("key"), fields = mapOf("field1" to Error.Builder("boom").build()))

    val (mergedRecord, changedKeys) = merger.merge(RecordMergerContext(existing, incoming, CacheHeaders.NONE))

    assertFalse(mergerCalled)
    assertEquals(mapOf("field1" to null), mergedRecord.fields)
    assertEquals(emptySet(), changedKeys)
  }

  /**
   * An existing `null` goes through the [FieldRecordMerger.FieldMerger] like any other value. Were it
   * treated as absent, the incoming value would be taken as is and the merger never consulted.
   */
  @Test
  fun fieldRecordMergerMergesAnExistingNull() {
    var seen: Pair<FieldRecordMerger.FieldInfo, FieldRecordMerger.FieldInfo>? = null
    val merger = FieldRecordMerger(
        object : FieldRecordMerger.FieldMerger {
          override fun mergeFields(
              existing: FieldRecordMerger.FieldInfo,
              incoming: FieldRecordMerger.FieldInfo,
          ): FieldRecordMerger.FieldInfo {
            seen = existing to incoming
            // Keep the existing null, which the "absent" path could not have done.
            return existing
          }
        },
    )
    val existing = Record(key = CacheKey("key"), fields = mapOf("field1" to null))
    val incoming = Record(key = CacheKey("key"), fields = mapOf("field1" to "value1"))

    val (mergedRecord, changedKeys) = merger.merge(RecordMergerContext(existing, incoming, CacheHeaders.NONE))

    assertNotNull(seen)
    assertEquals(null, seen.first.value)
    assertEquals("value1", seen.second.value)
    assertEquals(mapOf("field1" to null), mergedRecord.fields)
    assertEquals(setOf(CacheKey("key").fieldKey("field1")), changedKeys)
  }

  /**
   * A field absent from the existing record is taken as is: there is nothing to merge it with.
   */
  @Test
  fun fieldRecordMergerDoesNotMergeAnAbsentField() {
    var mergerCalled = false
    val merger = FieldRecordMerger(
        object : FieldRecordMerger.FieldMerger {
          override fun mergeFields(
              existing: FieldRecordMerger.FieldInfo,
              incoming: FieldRecordMerger.FieldInfo,
          ): FieldRecordMerger.FieldInfo {
            mergerCalled = true
            return incoming
          }
        },
    )
    val existing = Record(key = CacheKey("key"), fields = emptyMap())
    val incoming = Record(key = CacheKey("key"), fields = mapOf("field1" to null))

    val (mergedRecord, changedKeys) = merger.merge(RecordMergerContext(existing, incoming, CacheHeaders.NONE))

    assertFalse(mergerCalled)
    assertEquals(mapOf("field1" to null), mergedRecord.fields)
    assertEquals(setOf(CacheKey("key").fieldKey("field1")), changedKeys)
  }
}
