package com.apollographql.cache.normalized

import com.apollographql.apollo.annotations.ApolloInternal
import com.apollographql.apollo.mpp.currentTimeMillis
import com.apollographql.cache.normalized.api.CacheHeaders
import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.api.DefaultRecordMerger
import com.apollographql.cache.normalized.api.MaxAgeContext
import com.apollographql.cache.normalized.api.MaxAgeProvider
import com.apollographql.cache.normalized.api.NormalizedCache
import com.apollographql.cache.normalized.api.ReadOnlyNormalizedCache
import com.apollographql.cache.normalized.api.Record
import com.apollographql.cache.normalized.api.RecordValue
import com.apollographql.cache.normalized.api.expirationDate
import com.apollographql.cache.normalized.api.fieldKey
import com.apollographql.cache.normalized.api.receivedDate
import com.apollographql.cache.normalized.internal.OptimisticNormalizedCache
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlin.jvm.JvmOverloads
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
 * Emit records of this cache + of its chained caches.
 */
private fun NormalizedCache.loadAllRecordsChained(batchSize: Int): Flow<Record> = flow {
  var cache: ReadOnlyNormalizedCache? = this@loadAllRecordsChained
  while (cache != null) {
    // OptimisticNormalizedCache.loadAllRecords() already delegates to nextCache.loadAllRecords()
    if (cache !is OptimisticNormalizedCache) {
      emitAll(cache.loadAllRecords(batchSize))
    }
    cache = cache.nextCache
  }
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
@JvmOverloads
suspend fun NormalizedCache.removeStaleFields(
    maxAgeProvider: MaxAgeProvider,
    maxStale: Duration = Duration.ZERO,
    batchSize: Int = 100,
    clock: () -> Long = { currentTimeMillis() },
): RemovedFieldsAndRecords {
  var recordsToUpdate = mutableMapOf<CacheKey, Record>()
  val removedFields = mutableSetOf<String>()
  val removedRecords = mutableSetOf<CacheKey>()
  loadAllRecordsChained(batchSize).collect { record ->
    var recordCopy = record
    for (field in record.fields) {
      if (isFieldStale(field, record, maxAgeProvider, maxStale, clock)) {
        recordCopy -= field.key
        recordsToUpdate[record.key] = recordCopy
        removedFields.add(record.key.fieldKey(field.key))
      }
    }
    if (recordsToUpdate.size >= batchSize) {
      removedRecords += flushFieldRemovals(recordsToUpdate)
      recordsToUpdate = mutableMapOf()
    }
  }
  removedRecords += flushFieldRemovals(recordsToUpdate)
  return RemovedFieldsAndRecords(removedFields = removedFields, removedRecords = removedRecords)
}

/**
 * Saves [recordsToUpdate] (records with at least one field removed) to the cache.
 * Records that were left empty are removed. The other ones replace the existing records (by removing + merging).
 *
 * @return the records that were removed.
 */
private suspend fun NormalizedCache.flushFieldRemovals(recordsToUpdate: Map<CacheKey, Record>): Set<CacheKey> {
  if (recordsToUpdate.isEmpty()) {
    return emptySet()
  }
  remove(recordsToUpdate.keys, cascade = false)
  val emptyRecords = recordsToUpdate.values.filter { it.isEmptyRecord() }.toSet()
  val nonEmptyRecords = recordsToUpdate.values - emptyRecords
  if (nonEmptyRecords.isNotEmpty()) {
    merge(nonEmptyRecords, CacheHeaders.NONE, DefaultRecordMerger)
  }
  return emptyRecords.map { it.key }.toSet()
}

/**
 * Returns whether the field [fieldKey] of [record] is stale, considering both the client controlled max age
 * (via [maxAgeProvider]) and the server controlled expiration date.
 */
private suspend fun NormalizedCache.isFieldStale(
    field: Map.Entry<String, RecordValue>,
    record: Record,
    maxAgeProvider: MaxAgeProvider,
    maxStale: Duration,
    clock: () -> Long,
): Boolean {
  val (fieldKey, fieldValue) = field

  // Consider the client controlled max age
  val receivedDate = record.receivedDate(fieldKey)
  if (receivedDate != null) {
    val referencedType = guessType(fieldValue)
    if (referencedType == null) {
      // dangling reference: return true so it's removed
      return true
    }
    val currentDate = clock() / 1000
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
                    ),
                ),
                MaxAgeContext.Field(
                    name = fieldKey,
                    type = MaxAgeContext.Type(
                        name = referencedType,
                        isComposite = fieldValue is CacheKey,
                        implements = emptyList(),
                    ),
                ),
            ),
        ),
    ).inWholeSeconds
    val staleDuration = age - maxAge
    if (staleDuration >= maxStale.inWholeSeconds) {
      return true
    }
  }

  // Consider the server controlled max age
  val expirationDate = record.expirationDate(fieldKey)
  if (expirationDate != null) {
    val currentDate = clock() / 1000
    val staleDuration = currentDate - expirationDate
    if (staleDuration >= maxStale.inWholeSeconds) {
      return true
    }
  }
  return false
}

/**
 * Remove all stale fields in the store.
 * @see removeStaleFields
 */
@JvmOverloads
suspend fun ApolloStore.removeStaleFields(
    maxAgeProvider: MaxAgeProvider,
    maxStale: Duration = Duration.ZERO,
    batchSize: Int = 100,
): RemovedFieldsAndRecords {
  return accessCache { cache ->
    cache.removeStaleFields(maxAgeProvider = maxAgeProvider, maxStale = maxStale, batchSize = batchSize)
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
@JvmOverloads
suspend fun NormalizedCache.removeDanglingReferences(batchSize: Int = 100): RemovedFieldsAndRecords {
  val allRemovedFields = mutableSetOf<String>()
  val allRemovedRecords = mutableSetOf<CacheKey>()
  do {
    var recordsToUpdate = mutableMapOf<CacheKey, Record>()
    val removedFields = mutableSetOf<String>()
    loadAllRecordsChained(batchSize).collect { record ->
      var recordCopy = record
      for (field in record.fields) {
        if (isDanglingReference(field.value)) {
          recordCopy -= field.key
          recordsToUpdate[record.key] = recordCopy
          removedFields.add(record.key.fieldKey(field.key))
        }
      }
      if (recordsToUpdate.size >= batchSize) {
        allRemovedRecords += flushFieldRemovals(recordsToUpdate)
        recordsToUpdate = mutableMapOf()
      }
    }
    allRemovedRecords += flushFieldRemovals(recordsToUpdate)
    allRemovedFields += removedFields
  } while (removedFields.isNotEmpty())
  return RemovedFieldsAndRecords(removedFields = allRemovedFields, removedRecords = allRemovedRecords)
}

/**
 * Remove all dangling references in the store.
 * @see removeDanglingReferences
 */
@JvmOverloads
suspend fun ApolloStore.removeDanglingReferences(batchSize: Int = 100): RemovedFieldsAndRecords {
  return accessCache { cache ->
    cache.removeDanglingReferences(batchSize = batchSize)
  }
}

private suspend fun NormalizedCache.isDanglingReference(value: RecordValue): Boolean {
  return when (value) {
    is CacheKey -> loadRecord(value, CacheHeaders.NONE) == null
    is List<*> -> isAnyDangling(value)
    is Map<*, *> -> isAnyDangling(value.values)
    else -> false
  }
}

private suspend fun NormalizedCache.isAnyDangling(values: Collection<*>): Boolean {
  val cacheKeys = values.filterIsInstance<CacheKey>()
  val loadedKeys = if (cacheKeys.isEmpty()) emptySet() else loadRecords(cacheKeys, CacheHeaders.NONE).mapTo(mutableSetOf()) { it.key }
  return values.any { if (it is CacheKey) it !in loadedKeys else isDanglingReference(it) }
}

private fun Record.isEmptyRecord() = fields.isEmpty() || fields.size == 1 && fields.keys.first() == "__typename"

/**
 * Guesses the __typename of the record(s) referenced by [value].
 *
 * Returns `""` if [value] isn't a reference. Returns `null` if it is a reference but the referenced record can't be loaded (dangling
 * reference) - it may have been removed earlier in the same [removeStaleFields] run.
 */
private suspend fun NormalizedCache.guessType(value: RecordValue): String? {
  return when (value) {
    is List<*> -> {
      val first = value.firstOrNull() ?: return ""
      guessType(first)
    }

    is CacheKey -> {
      loadRecord(value, CacheHeaders.NONE)?.get("__typename") as? String
    }

    else -> {
      // We don't care about types of scalars, because it's not possible to configure a maxAge for them
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
@JvmOverloads
suspend fun NormalizedCache.garbageCollect(
    maxAgeProvider: MaxAgeProvider,
    maxStale: Duration = Duration.ZERO,
    batchSize: Int = 100,
    clock: () -> Long = { currentTimeMillis() },
): GarbageCollectResult {
  val removedStaleFields = removeStaleFields(
      maxAgeProvider = maxAgeProvider,
      maxStale = maxStale,
      batchSize = batchSize,
      clock = clock,
  )
  val removedDanglingReferences = removeDanglingReferences(batchSize = batchSize)
  val allRecords = allRecords().toMutableMap()
  return GarbageCollectResult(
      removedStaleFields = removedStaleFields,
      removedDanglingReferences = removedDanglingReferences,
      removedUnreachableRecords = removeUnreachableRecords(allRecords),
  )
}

/**
 * Perform garbage collection on the store.
 * @see garbageCollect
 */
@JvmOverloads
suspend fun ApolloStore.garbageCollect(
    maxAgeProvider: MaxAgeProvider,
    maxStale: Duration = Duration.ZERO,
    batchSize: Int = 100,
    clock: () -> Long = { currentTimeMillis() },
): GarbageCollectResult {
  return accessCache { cache ->
    cache.garbageCollect(maxAgeProvider, maxStale, batchSize, clock)
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
