package com.apollographql.cache.normalized.api

import kotlin.jvm.JvmStatic
import kotlin.jvm.JvmSuppressWildcards
import kotlin.reflect.KClass

/**
 * A provider of [Record] for reading requests from cache.
 *
 * To serialize a [Record] to a standardized form use recordAdapter() which handles call custom scalar
 * types registered on the ApolloClient
 *
 * If a [NormalizedCache] cannot return all the records needed to read a response, it will be considered a cache
 * miss.
 *
 * A [NormalizedCache] is recommended to implement support for [CacheHeaders] specified in the `cacheHeaders` of [merge] .
 *
 * A [NormalizedCache] can choose to store records in any manner.
 */
interface NormalizedCache : ReadOnlyNormalizedCache {
  /**
   * @param record       The [Record] to merge.
   * @param cacheHeaders The [CacheHeaders] associated with the request which generated this record.
   * @param recordMerger The [RecordMerger] to use when merging the record.
   * @return A set of record field keys that have changed. This set is returned by [RecordMerger.merge].
   */
  suspend fun merge(
      record: Record,
      cacheHeaders: CacheHeaders,
      recordMerger: RecordMerger,
  ): Set<String>

  /**
   * Calls through to [NormalizedCache.merge]. Implementations should override this method
   * if the underlying storage technology can offer an optimized manner to store multiple records.
   *
   * @param records The collection of Records to merge.
   * @param cacheHeaders The [CacheHeaders] associated with the request which generated this record.
   * @param recordMerger The [RecordMerger] to use when merging the records.
   * @return A set of record field keys that have changed. This set is returned by [RecordMerger.merge].
   */
  suspend fun merge(
      records: Collection<Record>,
      cacheHeaders: CacheHeaders,
      recordMerger: RecordMerger,
  ): Set<String>


  /**
   * Clears all records from the cache.
   */
  suspend fun clearAll()

  /**
   * Remove a record and potentially its referenced records from this cache and all chained caches
   *
   * @param cacheKey of record to be removed
   * @param cascade remove referenced records if true
   * @return `true` if at least one record was successfully removed, `false` otherwise
   */
  suspend fun remove(cacheKey: CacheKey, cascade: Boolean): Boolean

  /**
   * Calls through to [NormalizedCache.remove]. Implementations should override this method
   * if the underlying storage technology can offer an optimized manner to remove multiple records.
   *
   * @param cacheKeys of records to be removed
   * @param cascade remove referenced records if true
   * @return the number of records removed
   */
  suspend fun remove(cacheKeys: Collection<CacheKey>, cascade: Boolean): Int

  /**
   * Trims the cache if its size exceeds [maxSizeBytes]. The amount of data to remove is determined by [trimFactor].
   * The oldest records are removed according to their updated date.
   *
   * Optional operation.
   *
   * @param maxSizeBytes the size of the cache in bytes above which the cache should be trimmed.
   * @param trimFactor the factor of the cache size to trim.
   * @return the cache size in bytes after trimming or -1 if the operation is not supported.
   */
  suspend fun trim(maxSizeBytes: Long, trimFactor: Float = 0.1f): Long {
    return -1
  }

  companion object {
    @JvmStatic
    fun prettifyDump(dump: Map<@JvmSuppressWildcards KClass<*>, Map<CacheKey, Record>>, showMetadata: Boolean = false): String =
      dump.prettifyDump(showMetadata = showMetadata)

    private fun Any?.prettifyDump(level: Int = 0, showMetadata: Boolean): String {
      return buildString {
        when (this@prettifyDump) {
          is Record -> {
            if (showMetadata) {
              append("{\n")
              indent(level + 1)
              append("fields: ")
              append(fields.prettifyDump(level + 1, showMetadata))
              append("\n")
              indent(level + 1)
              append("metadata: ")
              append(metadata.prettifyDump(level + 1, showMetadata))
              append("\n")
              indent(level)
              append("}")
            } else {
              append(fields.prettifyDump(level, showMetadata))
            }
          }

          is List<*> -> {
            append("[")
            if (this@prettifyDump.isNotEmpty()) {
              append("\n")
              for (value in this@prettifyDump) {
                indent(level + 1)
                append(value.prettifyDump(level + 1, showMetadata))
                append("\n")
              }
              indent(level)
            }
            append("]")
          }

          is Map<*, *> -> {
            append("{")
            if (this@prettifyDump.isNotEmpty()) {
              append("\n")
              for ((key, value) in this@prettifyDump) {
                indent(level + 1)
                append(when (key) {
                  is KClass<*> -> key.simpleName
                  is CacheKey -> key.keyToString()
                  else -> key
                }
                )
                append(": ")
                append(value.prettifyDump(level + 1, showMetadata))
                append("\n")
              }
              indent(level)
            }
            append("}")
          }

          else -> append(this@prettifyDump)
        }
      }
    }

    private fun StringBuilder.indent(level: Int) = append("  ".repeat(level))
  }
}

