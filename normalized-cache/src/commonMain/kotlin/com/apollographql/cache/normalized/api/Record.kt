package com.apollographql.cache.normalized.api

import com.apollographql.apollo.annotations.ApolloInternal
import com.apollographql.apollo.api.json.ApolloJsonElement
import com.benasher44.uuid.Uuid

/**
 * A normalized entry that corresponds to a response object. Object fields are stored if they are a GraphQL Scalars. If
 * a field is a GraphQL Object a [CacheKey] will be stored instead.
 */
class Record(
    val key: CacheKey,
    val fields: Map<String, RecordValue>,
    val mutationId: Uuid? = null,

    /**
     * Arbitrary metadata that can be attached to each field.
     */
    val metadata: Map<String, Map<String, ApolloJsonElement>> = emptyMap(),

    /**
     * Size of the record in bytes. This is an optional field that can be set by the cache implementation for debug purposes, otherwise it
     * defaults to `-1`, meaning unknown size.
     */
    val sizeInBytes: Int = -1,
) : Map<String, Any?> by fields {
  /**
   * Returns a merge result record and a set of field keys which have changed, or were added.
   * A field key incorporates any GraphQL arguments in addition to the field name.
   */
  fun mergeWith(newRecord: Record): Pair<Record, Set<String>> {
    return DefaultRecordMerger.merge(RecordMergerContext(existing = this, incoming = newRecord, cacheHeaders = CacheHeaders.NONE))
  }


  /**
   * Returns a set of all field keys.
   * A field key incorporates any GraphQL arguments in addition to the field name.
   */
  fun fieldKeys(): Set<String> {
    return fields.keys.map { key.fieldKey(it) }.toSet()
  }

  /**
   * Returns the list of referenced cache fields
   */
  fun referencedFields(): List<CacheKey> {
    val result = mutableListOf<CacheKey>()
    val stack = fields.values.toMutableList()
    while (stack.isNotEmpty()) {
      when (val value = stack.removeAt(stack.size - 1)) {
        is CacheKey -> result.add(value)
        is Map<*, *> -> stack.addAll(value.values)
        is List<*> -> stack.addAll(value)
      }
    }
    return result
  }

  companion object {
    internal fun changedKeys(record1: Record, record2: Record): Set<String> {
      check(record1.key == record2.key) {
        "Cannot compute changed keys on record with different keys: '${record1.key}' - '${record2.key}'"
      }
      val keys1 = record1.fields.keys
      val keys2 = record2.fields.keys
      val intersection = keys1.intersect(keys2)

      val changed = (keys1 - intersection) + (keys2 - intersection) + intersection.filter {
        record1.fields[it] != record2.fields[it]
      }

      return changed.map { record1.key.fieldKey(it) }.toSet()
    }
  }
}

@ApolloInternal
fun Record.withDates(receivedDate: String?, expirationDate: String?): Record {
  if (receivedDate == null && expirationDate == null) {
    return this
  }
  return Record(
      key = key,
      fields = fields,
      mutationId = mutationId,
      metadata = metadata + fields.mapValues { (key, _) ->
        metadata[key].orEmpty() + buildMap {
          receivedDate?.let {
            put(ApolloCacheHeaders.RECEIVED_DATE, it.toLong())
          }
          expirationDate?.let {
            put(ApolloCacheHeaders.EXPIRATION_DATE, it.toLong())
          }
        }
      }
  )
}

@ApolloInternal
fun Record.withSizeInBytes(sizeInBytes: Int): Record {
  return Record(
      key = key,
      fields = fields,
      mutationId = mutationId,
      metadata = metadata,
      sizeInBytes = sizeInBytes,
  )
}

fun Record.receivedDate(field: String) = metadata[field]?.get(ApolloCacheHeaders.RECEIVED_DATE) as? Long

fun Record.expirationDate(field: String) = metadata[field]?.get(ApolloCacheHeaders.EXPIRATION_DATE) as? Long

/**
 * A typealias for a type-unsafe Kotlin representation of a Record value. This typealias is
 * mainly for internal documentation purposes and low-level manipulations and should
 * generally be avoided in application code.
 *
 * [RecordValue] can be any of:
 * - [com.apollographql.apollo.api.json.ApolloJsonElement]
 * - [CacheKey]
 * - [com.apollographql.apollo.api.Error]
 */
typealias RecordValue = Any?

fun Collection<Record>?.dependentKeys(): Set<String> {
  return this?.flatMap {
    it.fieldKeys()
  }?.toSet() ?: emptySet()
}
