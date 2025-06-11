package com.apollographql.cache.normalized.internal

import com.apollographql.cache.normalized.api.CacheHeaders
import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.api.NormalizedCache
import com.apollographql.cache.normalized.api.Record
import com.apollographql.cache.normalized.api.Record.Companion.changedKeys
import com.apollographql.cache.normalized.api.RecordMerger
import com.benasher44.uuid.Uuid
import kotlin.math.max
import kotlin.reflect.KClass

internal class OptimisticNormalizedCache(private val wrapped: NormalizedCache) : NormalizedCache {
  private val recordJournals = ConcurrentMap<CacheKey, RecordJournal>()

  override suspend fun loadRecord(key: CacheKey, cacheHeaders: CacheHeaders): Record? {
    val nonOptimisticRecord = wrapped.loadRecord(key, cacheHeaders)
    return nonOptimisticRecord.mergeJournalRecord(key)
  }

  override suspend fun loadRecords(keys: Collection<CacheKey>, cacheHeaders: CacheHeaders): Collection<Record> {
    val nonOptimisticRecords = wrapped.loadRecords(keys, cacheHeaders).associateBy { it.key }
    return keys.mapNotNull { key ->
      nonOptimisticRecords[key].mergeJournalRecord(key)
    }
  }

  override suspend fun merge(record: Record, cacheHeaders: CacheHeaders, recordMerger: RecordMerger): Set<String> {
    return wrapped.merge(record, cacheHeaders, recordMerger)
  }

  override suspend fun merge(records: Collection<Record>, cacheHeaders: CacheHeaders, recordMerger: RecordMerger): Set<String> {
    return wrapped.merge(records, cacheHeaders, recordMerger)
  }

  override suspend fun clearAll() {
    wrapped.clearAll()
    recordJournals.clear()
  }

  override suspend fun remove(cacheKey: CacheKey, cascade: Boolean): Boolean {
    return remove(setOf(cacheKey), cascade) > 0
  }

  override suspend fun remove(cacheKeys: Collection<CacheKey>, cascade: Boolean): Int {
    return wrapped.remove(cacheKeys, cascade) + internalRemove(cacheKeys, cascade)
  }

  override suspend fun trim(maxSizeBytes: Long, trimFactor: Float): Long {
    return wrapped.trim(maxSizeBytes, trimFactor)
  }

  private fun internalRemove(cacheKeys: Collection<CacheKey>, cascade: Boolean): Int {
    var total = 0
    val referencedCacheKeys = mutableSetOf<CacheKey>()
    for (cacheKey in cacheKeys) {
      val removedRecordJournal = recordJournals.remove(cacheKey)
      if (removedRecordJournal != null) {
        total++
        if (cascade) {
          for (cacheReference in removedRecordJournal.current.referencedFields()) {
            referencedCacheKeys += cacheReference
          }
        }
      }
    }
    if (referencedCacheKeys.isNotEmpty()) {
      total += internalRemove(referencedCacheKeys, cascade)
    }
    return total
  }

  fun addOptimisticUpdates(recordSet: Collection<Record>): Set<String> {
    return recordSet.flatMap {
      addOptimisticUpdate(it)
    }.toSet()
  }

  fun addOptimisticUpdate(record: Record): Set<String> {
    val journal = recordJournals[record.key]
    return if (journal == null) {
      recordJournals[record.key] = RecordJournal(record)
      record.fieldKeys()
    } else {
      journal.addPatch(record)
    }
  }

  fun removeOptimisticUpdates(mutationId: Uuid): Set<String> {
    val changedCacheKeys = mutableSetOf<String>()
    val keys = HashSet(recordJournals.keys) // local copy to avoid concurrent modification
    keys.forEach {
      val recordJournal = recordJournals[it] ?: return@forEach
      val result = recordJournal.removePatch(mutationId)
      changedCacheKeys.addAll(result.changedKeys)
      if (result.isEmpty) {
        recordJournals.remove(it)
      }
    }
    return changedCacheKeys
  }

  override suspend fun dump(): Map<KClass<*>, Map<CacheKey, Record>> {
    return mapOf(this::class to recordJournals.mapValues { (_, journal) -> journal.current }) + wrapped.dump()
  }

  private fun Record?.mergeJournalRecord(key: CacheKey): Record? {
    val journal = recordJournals[key]
    return if (journal != null) {
      this?.mergeWith(journal.current)?.first ?: journal.current
    } else {
      this
    }
  }

  private class RemovalResult(
      val changedKeys: Set<String>,
      val isEmpty: Boolean,
  )

  private class RecordJournal(record: Record) {
    /**
     * The latest value of the record made by applying all the patches.
     */
    var current: Record = record

    /**
     * A list of chronological patches applied to the record.
     */
    private val patches = mutableListOf(record)

    /**
     * Adds a new patch on top of all the previous ones.
     */
    fun addPatch(record: Record): Set<String> {
      val (mergedRecord, changedKeys) = current.mergeWith(record)
      current = mergedRecord
      patches.add(record)
      return changedKeys
    }

    /**
     * Lookup record by mutation id, if it's found removes it from the history and
     * computes the new current record.
     *
     * @return the changed keys or null if
     */
    fun removePatch(mutationId: Uuid): RemovalResult {
      val recordIndex = patches.indexOfFirst { mutationId == it.mutationId }
      if (recordIndex == -1) {
        // The mutation did not impact this Record
        return RemovalResult(emptySet(), false)
      }

      if (patches.size == 1) {
        // The mutation impacted this Record and it was the only one in the history
        return RemovalResult(current.fieldKeys(), true)
      }

      /**
       * There are multiple patches, go over them and compute the new current value
       * Remember the oldRecord so that we can compute the changed keys
       */
      val oldRecord = current

      patches.removeAt(recordIndex).key

      var cur: Record? = null
      val start = max(0, recordIndex - 1)
      for (i in start until patches.size) {
        val record = patches[i]
        if (cur == null) {
          cur = record
        } else {
          val (mergedRecord, _) = cur.mergeWith(record)
          cur = mergedRecord
        }
      }
      current = cur!!

      return RemovalResult(changedKeys(oldRecord, current), false)
    }
  }
}
