package com.apollographql.cache.normalized

import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.api.Record
import com.apollographql.cache.normalized.api.fieldKey
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [Record.Companion.changedKeys] tells [com.apollographql.cache.normalized.internal.OptimisticNormalizedCache]
 * which watchers to notify when an optimistic update is rolled back, so a key it fails to report is a
 * watcher that never refreshes.
 *
 * A `null` field value is what makes this subtle: it is indistinguishable from an absent field by
 * lookup alone, so these tests pin down every combination of absent / null / non-null on both sides.
 */
class RecordChangedKeysTest {
  private val key = CacheKey("User:1")

  private fun changedKeys(fields1: Map<String, Any?>, fields2: Map<String, Any?>): Set<String> {
    return Record.changedKeys(
        Record(key = key, fields = fields1),
        Record(key = key, fields = fields2),
    )
  }

  @Test
  fun sameFieldsAreNotChanged() {
    assertEquals(
        emptySet(),
        changedKeys(
            mapOf("name" to "John", "age" to 42),
            mapOf("name" to "John", "age" to 42),
        ),
    )
  }

  @Test
  fun differentValueIsChanged() {
    assertEquals(
        setOf(key.fieldKey("name")),
        changedKeys(
            mapOf("name" to "John", "age" to 42),
            mapOf("name" to "Jane", "age" to 42),
        ),
    )
  }

  @Test
  fun fieldOnlyInFirstRecordIsChanged() {
    assertEquals(
        setOf(key.fieldKey("age")),
        changedKeys(
            mapOf("name" to "John", "age" to 42),
            mapOf("name" to "John"),
        ),
    )
  }

  @Test
  fun fieldOnlyInSecondRecordIsChanged() {
    assertEquals(
        setOf(key.fieldKey("age")),
        changedKeys(
            mapOf("name" to "John"),
            mapOf("name" to "John", "age" to 42),
        ),
    )
  }

  @Test
  fun nullInBothRecordsIsNotChanged() {
    assertEquals(
        emptySet(),
        changedKeys(
            mapOf("name" to null),
            mapOf("name" to null),
        ),
    )
  }

  @Test
  fun nullInFirstRecordAndAbsentFromSecondIsChanged() {
    // The ambiguous case: `fields2["name"]` is null either way, so only `containsKey` separates a
    // field that was removed from one that is still there and null.
    assertEquals(
        setOf(key.fieldKey("name")),
        changedKeys(
            mapOf("name" to null),
            emptyMap(),
        ),
    )
  }

  @Test
  fun absentFromFirstRecordAndNullInSecondIsChanged() {
    assertEquals(
        setOf(key.fieldKey("name")),
        changedKeys(
            emptyMap(),
            mapOf("name" to null),
        ),
    )
  }

  @Test
  fun nullToNonNullIsChanged() {
    assertEquals(
        setOf(key.fieldKey("name")),
        changedKeys(
            mapOf("name" to null),
            mapOf("name" to "John"),
        ),
    )
  }

  @Test
  fun nonNullToNullIsChanged() {
    assertEquals(
        setOf(key.fieldKey("name")),
        changedKeys(
            mapOf("name" to "John"),
            mapOf("name" to null),
        ),
    )
  }

  @Test
  fun changesOnBothSidesAreAllReported() {
    assertEquals(
        setOf(key.fieldKey("onlyIn1"), key.fieldKey("onlyIn2"), key.fieldKey("differing")),
        changedKeys(
            mapOf("same" to "s", "differing" to "a", "onlyIn1" to null),
            mapOf("same" to "s", "differing" to "b", "onlyIn2" to null),
        ),
    )
  }
}
