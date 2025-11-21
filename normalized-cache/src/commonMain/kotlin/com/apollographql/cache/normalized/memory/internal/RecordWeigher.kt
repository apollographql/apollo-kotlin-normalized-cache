package com.apollographql.cache.normalized.memory.internal

import com.apollographql.apollo.api.Error
import com.apollographql.apollo.api.json.JsonNumber
import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.api.Record
import com.apollographql.cache.normalized.api.RecordValue

internal object RecordWeigher {
  private const val SIZE_OF_BOOLEAN = 16
  private const val SIZE_OF_INT = 4
  private const val SIZE_OF_LONG = 8
  private const val SIZE_OF_DOUBLE = 8
  private const val SIZE_OF_ARRAY_OVERHEAD = 16
  private const val SIZE_OF_MAP_OVERHEAD = 16
  private const val SIZE_OF_RECORD_OVERHEAD = 16
  private const val SIZE_OF_CACHE_KEY_OVERHEAD = 16
  private const val SIZE_OF_NULL = 4
  private const val SIZE_OF_ERROR_OVERHEAD = 16

  fun calculateBytes(record: Record): Int {
    var size = SIZE_OF_RECORD_OVERHEAD + record.key.key.length
    for ((key, value) in record.fields) {
      size += key.length + weighField(value)
    }
    size += weighField(record.metadata)
    return size
  }

  private fun weighField(field: RecordValue): Int {
    /*
     * Note: for Strings we use the character length where we should use the UTF-8 size,
     * but this is a good enough estimation for the weight, and avoids a bit of overhead.
     */
    return when (field) {
      null -> SIZE_OF_NULL
      is String -> field.length
      is Boolean -> SIZE_OF_BOOLEAN
      is Number -> {
        // Using `is` on numbers always return true in JS, so we use the class instead
        when (field::class) {
          Int::class -> SIZE_OF_INT
          Long::class -> SIZE_OF_LONG
          else -> SIZE_OF_DOUBLE
        }
      }

      is JsonNumber -> field.value.length + SIZE_OF_LONG
      /**
       * Custom scalars with a json object representation are stored directly in the record
       */
      is Map<*, *> -> {
        SIZE_OF_MAP_OVERHEAD + field.keys.sumOf { weighField(it) } + field.values.sumOf { weighField(it) }
      }

      is List<*> -> {
        SIZE_OF_ARRAY_OVERHEAD + field.sumOf { weighField(it) }
      }

      is CacheKey -> {
        SIZE_OF_CACHE_KEY_OVERHEAD + field.key.length
      }

      is Error -> {
        SIZE_OF_ERROR_OVERHEAD +
            field.message.length +
            (field.locations?.size ?: 0) * SIZE_OF_INT * 2 +
            weighField(field.path) +
            weighField(field.extensions) +
            weighField(field.nonStandardFields)
      }

      else -> error("Unknown field type in Record: '$field'")
    }
  }
}
