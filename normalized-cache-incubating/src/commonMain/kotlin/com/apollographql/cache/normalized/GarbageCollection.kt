package com.apollographql.cache.normalized

import com.apollographql.apollo.annotations.ApolloInternal
import com.apollographql.apollo.mpp.currentTimeMillis
import com.apollographql.cache.normalized.api.CacheHeaders
import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.api.DefaultRecordMerger
import com.apollographql.cache.normalized.api.MaxAgeContext
import com.apollographql.cache.normalized.api.MaxAgeProvider
import com.apollographql.cache.normalized.api.Record
import com.apollographql.cache.normalized.api.RecordValue
import com.apollographql.cache.normalized.api.expirationDate
import com.apollographql.cache.normalized.api.receivedDate
import com.apollographql.cache.normalized.internal.OptimisticNormalizedCache
import kotlin.time.Duration

@ApolloInternal
fun ApolloStore.getReachableCacheKeys(): Set<CacheKey> {
  fun ApolloStore.getReachableCacheKeys(roots: List<CacheKey>, reachableCacheKeys: MutableSet<CacheKey>) {
    val records = accessCache { cache -> cache.loadRecords(roots.map { it.key }, CacheHeaders.NONE) }.associateBy { it.key }
    val cacheKeysToCheck = mutableListOf<CacheKey>()
    for ((key, record) in records) {
      reachableCacheKeys.add(CacheKey(key))
      cacheKeysToCheck.addAll(record.referencedFields())
    }
    if (cacheKeysToCheck.isNotEmpty()) {
      getReachableCacheKeys(cacheKeysToCheck, reachableCacheKeys)
    }
  }

  return mutableSetOf<CacheKey>().also { reachableCacheKeys ->
    getReachableCacheKeys(listOf(CacheKey.rootKey()), reachableCacheKeys)
  }
}

@ApolloInternal
fun ApolloStore.allRecords(): Map<String, Record> {
  return accessCache { cache ->
    val dump = cache.dump()
    val classKey = dump.keys.first { it != OptimisticNormalizedCache::class }
    dump[classKey]!!
  }
}

fun ApolloStore.removeUnreachableRecords(): Set<CacheKey> {
  val unreachableCacheKeys = allRecords().keys.map { CacheKey(it) } - getReachableCacheKeys()
  remove(unreachableCacheKeys, cascade = false)
  return unreachableCacheKeys.toSet()
}

/**
 * Remove all stale fields in the cache.
 * A field is stale if its received date is older than its max age (configurable via [maxAgeProvider]) or if its expiration date has
 * passed. A maximum staleness can be passed.
 *
 * Received dates are stored by calling `storeReceiveDate(true)` on your `ApolloClient`.
 *
 * Expiration dates are stored by calling `storeExpirationDate(true)` on your `ApolloClient`.
 *
 * When all fields of a record are removed, the record itself is removed too.
 *
 * This can result in unreachable records, and dangling references.
 *
 * @return the field keys that were removed.
 */
fun ApolloStore.removeStaleFields(
    maxAgeProvider: MaxAgeProvider,
    maxStale: Duration = Duration.ZERO,
): Set<String> {
  val allRecords: Map<String, Record> = allRecords()
  val recordsToUpdate = mutableMapOf<String, Record>()
  val removedKeys = mutableSetOf<String>()
  for (record in allRecords.values) {
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
                    MaxAgeContext.Field(name = "", type = record["__typename"] as? String ?: "", isTypeComposite = true),
                    MaxAgeContext.Field(name = field.key, type = field.value.guessType(allRecords), isTypeComposite = field.value is CacheKey),
                )
            )
        ).inWholeSeconds
        val staleDuration = age - maxAge
        if (staleDuration >= maxStale.inWholeSeconds) {
          recordCopy -= field.key
          recordsToUpdate[record.key] = recordCopy
          removedKeys.add(record.key + "." + field.key)
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
          removedKeys.add(record.key + "." + field.key)
        }
      }
    }
  }
  if (recordsToUpdate.isNotEmpty()) {
    accessCache { cache ->
      cache.remove(recordsToUpdate.keys.map { CacheKey(it) }, cascade = false)
      val nonEmptyRecords = recordsToUpdate.values.filterNot { it.isEmptyRecord() }
      if (nonEmptyRecords.isNotEmpty()) {
        cache.merge(nonEmptyRecords, CacheHeaders.NONE, DefaultRecordMerger)
      }
    }
  }
  return removedKeys
}

private fun Record.isEmptyRecord() = fields.isEmpty() || fields.size == 1 && fields.keys.first() == "__typename"

private fun RecordValue.guessType(allRecords: Map<String, Record>): String {
  return when (this) {
    is List<*> -> {
      val first = firstOrNull() ?: return ""
      first.guessType(allRecords)
    }

    is CacheKey -> {
      allRecords[key]?.get("__typename") as? String ?: ""
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
