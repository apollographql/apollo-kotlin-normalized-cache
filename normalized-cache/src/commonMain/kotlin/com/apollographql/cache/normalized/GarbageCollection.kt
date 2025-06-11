package com.apollographql.cache.normalized

import com.apollographql.apollo.annotations.ApolloInternal
import com.apollographql.apollo.mpp.currentTimeMillis
import com.apollographql.cache.normalized.api.CacheHeaders
import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.api.DefaultRecordMerger
import com.apollographql.cache.normalized.api.MaxAgeContext
import com.apollographql.cache.normalized.api.MaxAgeProvider
import com.apollographql.cache.normalized.api.NormalizedCache
import com.apollographql.cache.normalized.api.Record
import com.apollographql.cache.normalized.api.RecordValue
import com.apollographql.cache.normalized.api.expirationDate
import com.apollographql.cache.normalized.api.fieldKey
import com.apollographql.cache.normalized.api.receivedDate
import kotlin.time.Duration

@ApolloInternal
fun Map<CacheKey, Record>.getReachableCacheKeys(): Set<CacheKey> {
  fun Map<CacheKey, Record>.getReachableCacheKeys(roots: List<CacheKey>, reachableCacheKeys: MutableSet<CacheKey>) {
    val records = roots.mapNotNull { this[it] }
    val cacheKeysToCheck = mutableListOf<CacheKey>()
    for (record in records) {
      reachableCacheKeys.add(record.key)
      cacheKeysToCheck.addAll(record.referencedFields() - reachableCacheKeys)
    }
    if (cacheKeysToCheck.isNotEmpty()) {
      getReachableCacheKeys(cacheKeysToCheck, reachableCacheKeys)
    }
  }

  return mutableSetOf<CacheKey>().also { reachableCacheKeys ->
    getReachableCacheKeys(listOf(CacheKey.QUERY_ROOT, CacheKey.MUTATION_ROOT, CacheKey.SUBSCRIPTION_ROOT), reachableCacheKeys)
  }
}

@ApolloInternal
suspend fun NormalizedCache.allRecords(): Map<CacheKey, Record> {
  return dump().values.fold(emptyMap()) { acc, map -> acc + map }
}

/**
 * Remove all unreachable records in the cache.
 * A record is unreachable if there exists no chain of references from the root record to it.
 *
 * @return the cache keys that were removed.
 */
suspend fun NormalizedCache.removeUnreachableRecords(): Set<CacheKey> {
  val allRecords = allRecords()
  return removeUnreachableRecords(allRecords)
}

private suspend fun NormalizedCache.removeUnreachableRecords(allRecords: Map<CacheKey, Record>): Set<CacheKey> {
  val unreachableCacheKeys = allRecords.keys - allRecords.getReachableCacheKeys()
  remove(unreachableCacheKeys, cascade = false)
  return unreachableCacheKeys.toSet()
}

/**
 * Remove all unreachable records in the store.
 * @see removeUnreachableRecords
 */
suspend fun ApolloStore.removeUnreachableRecords(): Set<CacheKey> {
  return accessCache { cache ->
    cache.removeUnreachableRecords()
  }
}

/**
 * Remove all stale fields in the cache.
 * A field is stale if its received date is older than its max age (configurable via [maxAgeProvider]) or if its expiration date has
 * passed. A maximum staleness can be passed.
 *
 * Received dates are stored by calling `storeReceivedDate(true)` on your `ApolloClient`.
 *
 * Expiration dates are stored by calling `storeExpirationDate(true)` on your `ApolloClient`.
 *
 * When all fields of a record are stale, the record itself is removed.
 *
 * This operation can result in unreachable records, and dangling references.
 *
 * @return the fields and records that were removed.
 */
suspend fun NormalizedCache.removeStaleFields(
    maxAgeProvider: MaxAgeProvider,
    maxStale: Duration = Duration.ZERO,
): RemovedFieldsAndRecords {
  val allRecords = allRecords().toMutableMap()
  return removeStaleFields(allRecords, maxAgeProvider, maxStale)
}

private suspend fun NormalizedCache.removeStaleFields(
    allRecords: MutableMap<CacheKey, Record>,
    maxAgeProvider: MaxAgeProvider,
    maxStale: Duration,
): RemovedFieldsAndRecords {
  val recordsToUpdate = mutableMapOf<CacheKey, Record>()
  val removedFields = mutableSetOf<String>()
  for (record in allRecords.values.toList()) {
    var recordCopy = record
    for (field in record.fields) {
      // Consider the client controlled max age
      val receivedDate = record.receivedDate(field.key)
      if (receivedDate != null) {
        val currentDate = currentTimeMillis() / 1000
        val age = currentDate - receivedDate
        val maxAge = maxAgeProvider.getMaxAge(
            MaxAgeContext(
                listOf(
                    MaxAgeContext.Field(
                        name = "",
                        type = MaxAgeContext.Type(
                            name = record["__typename"] as? String ?: "",
                            isComposite = true,
                            implements = emptyList(),
                        )
                    ),
                    MaxAgeContext.Field(
                        name = field.key,
                        type = MaxAgeContext.Type(
                            name = field.value.guessType(allRecords),
                            isComposite = field.value is CacheKey,
                            implements = emptyList(),
                        ),
                    )
                )
            )
        ).inWholeSeconds
        val staleDuration = age - maxAge
        if (staleDuration >= maxStale.inWholeSeconds) {
          recordCopy -= field.key
          recordsToUpdate[record.key] = recordCopy
          removedFields.add(record.key.fieldKey((field.key)))
          if (recordCopy.isEmptyRecord()) {
            allRecords.remove(record.key)
          } else {
            allRecords[record.key] = recordCopy
          }
          continue
        }
      }

      // Consider the server controlled max age
      val expirationDate = record.expirationDate(field.key)
      if (expirationDate != null) {
        val currentDate = currentTimeMillis() / 1000
        val staleDuration = currentDate - expirationDate
        if (staleDuration >= maxStale.inWholeSeconds) {
          recordCopy -= field.key
          recordsToUpdate[record.key] = recordCopy
          removedFields.add(record.key.fieldKey(field.key))
          if (recordCopy.isEmptyRecord()) {
            allRecords.remove(record.key)
          } else {
            allRecords[record.key] = recordCopy
          }
        }
      }
    }
  }
  if (recordsToUpdate.isNotEmpty()) {
    remove(recordsToUpdate.keys, cascade = false)
    val emptyRecords = recordsToUpdate.values.filter { it.isEmptyRecord() }.toSet()
    val nonEmptyRecords = recordsToUpdate.values - emptyRecords
    if (nonEmptyRecords.isNotEmpty()) {
      merge(nonEmptyRecords, CacheHeaders.NONE, DefaultRecordMerger)
    }
    return RemovedFieldsAndRecords(
        removedFields = removedFields,
        removedRecords = emptyRecords.map { it.key }.toSet()
    )
  }
  return RemovedFieldsAndRecords(removedFields = emptySet(), removedRecords = emptySet())
}

/**
 * Remove all stale fields in the store.
 * @see removeStaleFields
 */
suspend fun ApolloStore.removeStaleFields(
    maxAgeProvider: MaxAgeProvider,
    maxStale: Duration = Duration.ZERO,
): RemovedFieldsAndRecords {
  return accessCache { cache ->
    cache.removeStaleFields(maxAgeProvider, maxStale)
  }
}

/**
 * Remove all dangling references in the cache.
 * A field is a dangling reference if its value (or, for lists, any of its values) is a reference to a record that does not exist.
 *
 * When all fields of a record are dangling references, the record itself is removed.
 *
 * This operation can result in unreachable records.
 *
 * @return the fields and records that were removed.
 */
suspend fun NormalizedCache.removeDanglingReferences(): RemovedFieldsAndRecords {
  val allRecords: MutableMap<CacheKey, Record> = allRecords().toMutableMap()
  return removeDanglingReferences(allRecords)
}

private suspend fun NormalizedCache.removeDanglingReferences(allRecords: MutableMap<CacheKey, Record>): RemovedFieldsAndRecords {
  val recordsToUpdate = mutableMapOf<CacheKey, Record>()
  val allRemovedFields = mutableSetOf<String>()
  do {
    val removedFields = mutableSetOf<String>()
    for (record in allRecords.values.toList()) {
      var recordCopy = record
      for (field in record.fields) {
        if (field.value.isDanglingReference(allRecords)) {
          recordCopy -= field.key
          recordsToUpdate[record.key] = recordCopy
          removedFields.add(record.key.fieldKey(field.key))
          if (recordCopy.isEmptyRecord()) {
            allRecords.remove(record.key)
          } else {
            allRecords[record.key] = recordCopy
          }
        }
      }
    }
    allRemovedFields.addAll(removedFields)
  } while (removedFields.isNotEmpty())
  if (recordsToUpdate.isNotEmpty()) {
    remove(recordsToUpdate.keys, cascade = false)
    val emptyRecords = recordsToUpdate.values.filter { it.isEmptyRecord() }.toSet()
    val nonEmptyRecords = recordsToUpdate.values - emptyRecords
    if (nonEmptyRecords.isNotEmpty()) {
      merge(nonEmptyRecords, CacheHeaders.NONE, DefaultRecordMerger)
    }
    return RemovedFieldsAndRecords(
        removedFields = allRemovedFields,
        removedRecords = emptyRecords.map { it.key }.toSet()
    )
  }
  return RemovedFieldsAndRecords(removedFields = emptySet(), removedRecords = emptySet())
}

/**
 * Remove all dangling references in the store.
 * @see removeDanglingReferences
 */
suspend fun ApolloStore.removeDanglingReferences(): RemovedFieldsAndRecords {
  return accessCache { cache ->
    cache.removeDanglingReferences()
  }
}

private fun RecordValue.isDanglingReference(allRecords: Map<CacheKey, Record>): Boolean {
  return when (this) {
    is CacheKey -> allRecords[this] == null
    is List<*> -> any { it.isDanglingReference(allRecords) }
    is Map<*, *> -> values.any { it.isDanglingReference(allRecords) }
    else -> false
  }
}

private fun Record.isEmptyRecord() = fields.isEmpty() || fields.size == 1 && fields.keys.first() == "__typename"

private fun RecordValue.guessType(allRecords: Map<CacheKey, Record>): String {
  return when (this) {
    is List<*> -> {
      val first = firstOrNull() ?: return ""
      first.guessType(allRecords)
    }

    is CacheKey -> {
      allRecords[this]?.get("__typename") as? String ?: ""
    }

    else -> {
      ""
    }
  }
}

private operator fun Record.minus(key: String): Record {
  return Record(
      key = this.key,
      fields = this.fields - key,
      metadata = this.metadata - key,
  )
}

/**
 * Perform garbage collection on the cache.
 *
 * This is a convenience method that calls [removeStaleFields], [removeDanglingReferences], and [removeUnreachableRecords].
 *
 * @param maxAgeProvider the max age provider to use for [removeStaleFields]
 * @param maxStale the maximum staleness to use for [removeStaleFields]
 */
suspend fun NormalizedCache.garbageCollect(
    maxAgeProvider: MaxAgeProvider,
    maxStale: Duration = Duration.ZERO,
): GarbageCollectResult {
  val allRecords = allRecords().toMutableMap()
  return GarbageCollectResult(
      removedStaleFields = removeStaleFields(allRecords, maxAgeProvider, maxStale),
      removedDanglingReferences = removeDanglingReferences(allRecords),
      removedUnreachableRecords = removeUnreachableRecords(allRecords)
  )
}

/**
 * Perform garbage collection on the store.
 * @see garbageCollect
 */
suspend fun ApolloStore.garbageCollect(
    maxAgeProvider: MaxAgeProvider,
    maxStale: Duration = Duration.ZERO,
): GarbageCollectResult {
  return accessCache { cache ->
    cache.garbageCollect(maxAgeProvider, maxStale)
  }
}

class RemovedFieldsAndRecords(
    val removedFields: Set<String>,
    val removedRecords: Set<CacheKey>,
)

class GarbageCollectResult(
    val removedStaleFields: RemovedFieldsAndRecords,
    val removedDanglingReferences: RemovedFieldsAndRecords,
    val removedUnreachableRecords: Set<CacheKey>,
)
