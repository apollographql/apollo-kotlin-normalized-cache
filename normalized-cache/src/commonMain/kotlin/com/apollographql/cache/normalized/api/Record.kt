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
    return buildSet(fields.size) {
      for (fieldName in fields.keys) {
        add(key.fieldKey(fieldName))
      }
    }
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

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is Record) return false

    if (key != other.key) return false
    if (fields != other.fields) return false
    if (metadata != other.metadata) return false

    return true
  }

  override fun hashCode(): Int {
    var result = key.hashCode()
    result = 31 * result + fields.hashCode()
    result = 31 * result + metadata.hashCode()
    return result
  }

  companion object {
    internal fun changedKeys(record1: Record, record2: Record): Set<String> {
      val fields1 = record1.fields
      val fields2 = record2.fields
      return buildSet {
        for ((fieldName, value1) in fields1) {
          val value2 = fields2[fieldName]
          // Differing values mean the field changed, and so does a field missing from `fields2`. A
          // null `value2` is ambiguous between the two, so use `containsKey` to disambiguate.
          if (value1 != value2 || (value1 == null && !fields2.containsKey(fieldName))) {
            add(record1.key.fieldKey(fieldName))
          }
        }
        for (fieldName in fields2.keys) {
          if (!fields1.containsKey(fieldName)) {
            add(record1.key.fieldKey(fieldName))
          }
        }
      }
    }
  }
}

@ApolloInternal
fun Record.withDates(receivedDate: String?, expirationDate: String?): Record {
  if (receivedDate == null && expirationDate == null) {
    return this
  }
  val dates = buildMap<String, ApolloJsonElement>(2) {
    receivedDate?.let {
      put(ApolloCacheHeaders.RECEIVED_DATE, it.toLong())
    }
    expirationDate?.let {
      put(ApolloCacheHeaders.EXPIRATION_DATE, it.toLong())
    }
  }
  return Record(
      key = key,
      fields = fields,
      mutationId = mutationId,
      metadata = metadata + fields.mapValues { (key, _) ->
        metadata[key].orEmpty() + dates
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

/**
 * Returns the set of all field keys of all the given records.
 * A field key incorporates any GraphQL arguments in addition to the field name.
 */
fun Collection<Record>?.dependentKeys(): Set<String> {
  if (this == null) {
    return emptySet()
  }
  return buildSet {
    for (record in this@dependentKeys) {
      for (fieldName in record.fields.keys) {
        add(record.key.fieldKey(fieldName))
      }
    }
  }
}
