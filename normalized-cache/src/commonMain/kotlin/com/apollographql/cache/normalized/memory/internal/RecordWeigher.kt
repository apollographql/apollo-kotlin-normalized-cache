package com.apollographql.cache.normalized.memory.internal

import com.apollographql.apollo.api.Error
import com.apollographql.apollo.api.json.JsonNumber
import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.api.Record
import com.apollographql.cache.normalized.api.RecordValue
import com.benasher44.uuid.Uuid

/**
 * A [RecordWeigher] estimates the size of a [Record].
 *
 * The estimation is a best effort estimation. Depending on the JVM and options, an error margin is expected.
 */
internal object RecordWeigher {
  fun calculateBytes(record: Record): Int {
    return 32 +
        weighField(record.fields) +
        weighField(record.key) +
        weighField(record.metadata) +
        weighField(record.mutationId) +
        weighField(record.sizeInBytes)
  }

  private fun weighField(field: RecordValue): Int {

    return when (field) {
      null -> 4
      is String -> {
        /**
         * String
         *   12 header
         *   4 hash
         *   1 coder
         *   1 hashIsZero
         *   4 value ref
         *   2 padding
         * byte[]
         *   12 header
         *   length data // This assumes 1 byte/char, which is only valid in certain circumstances.
         **/
        46 + field.length
      }
      is Boolean -> {
        16
      }
      is Number -> {
        // Using `is` on numbers always return true in JS, so we use the class instead
        when (field::class) {
          Int::class -> {
            16
          }
          Long::class -> {
            24
          }
          else -> {
            24
          }
        }
      }

      is JsonNumber -> 16 + weighField(field.value)
      /**
       * Custom scalars with a JSON object representation are stored directly in the record
       */
      is Map<*, *> -> {
        /**
         * 56 LinkedHashMap
         * 40 LinkedHashMap.Entry
         */
        56 + field.keys.sumOf { weighField(it) } + field.values.sumOf { weighField(it) } + field.size * 40
      }

      is List<*> -> {
        /**
         * 24 Arraylist
         */
        24 + field.sumOf { weighField(it) }
      }

      is CacheKey -> {
        16 + weighField(field.key)
      }

      is Error -> {
        32 +
            weighField(field.message) +
            weighField(field.locations) +
            weighField(field.path) +
            weighField(field.extensions) +
            weighField(field.nonStandardFields)
      }
      is Error.Location -> {
        12 + 32
      }
      is Uuid -> {
        32
      }

      else -> error("Unknown field type in Record: '$field'")
    }
  }
}
