package com.apollographql.cache.normalized.sql

import com.apollographql.apollo.exception.apolloExceptionHandler
import com.apollographql.cache.normalized.api.ApolloCacheHeaders
import com.apollographql.cache.normalized.api.CacheHeaders
import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.api.NormalizedCache
import com.apollographql.cache.normalized.api.Record
import com.apollographql.cache.normalized.api.RecordMerger
import com.apollographql.cache.normalized.api.RecordMergerContext
import com.apollographql.cache.normalized.api.withDates
import com.apollographql.cache.normalized.api.withSizeInBytes
import com.apollographql.cache.normalized.sql.internal.RecordDatabase
import com.apollographql.cache.normalized.sql.internal.RecordSerializer
import com.apollographql.cache.normalized.sql.internal.parametersMax
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlin.reflect.KClass

class SqlNormalizedCache internal constructor(
    private val recordDatabase: RecordDatabase,
) : NormalizedCache {

  override suspend fun loadRecord(key: CacheKey, cacheHeaders: CacheHeaders): Record? {
    return loadRecords(keys = listOf(key), cacheHeaders = cacheHeaders).firstOrNull()
  }

  override suspend fun loadRecords(keys: Collection<CacheKey>, cacheHeaders: CacheHeaders): Collection<Record> {
    if (keys.isEmpty() || cacheHeaders.headerValue(ApolloCacheHeaders.MEMORY_CACHE_ONLY) == "true") {
      return emptyList()
    }
    return try {
      selectRecords(keys)
    } catch (t: Throwable) {
      // Unable to read the records from the database, it is possibly corrupted - treat this as a cache miss
      apolloExceptionHandler(Exception("Unable to read records from the database", t))
      emptyList()
    }
  }

  override suspend fun loadAllRecords(): Flow<Record> {
    return try {
      recordDatabase.init()
      recordDatabase.selectAllRecords()
    } catch (t: Throwable) {
      // Unable to clear the records from the database, it is possibly corrupted
      apolloExceptionHandler(Exception("Unable to read records from the database", t))
      emptyFlow()
    }
  }

  override suspend fun clearAll() {
    try {
      recordDatabase.init()
      recordDatabase.deleteAllRecords()
    } catch (t: Throwable) {
      // Unable to clear the records from the database, it is possibly corrupted
      apolloExceptionHandler(Exception("Unable to clear records from the database", t))
    }
  }

  override suspend fun remove(cacheKey: CacheKey, cascade: Boolean): Boolean {
    return remove(cacheKeys = listOf(cacheKey), cascade = cascade) > 0
  }

  override suspend fun remove(cacheKeys: Collection<CacheKey>, cascade: Boolean): Int {
    if (cacheKeys.isEmpty()) {
      return 0
    }
    return try {
      recordDatabase.init()
      recordDatabase.transaction {
        internalDeleteRecords(cacheKeys.map { it.key }, cascade)
      }
    } catch (t: Throwable) {
      // Unable to delete the records from the database, it is possibly corrupted
      apolloExceptionHandler(Exception("Unable to delete records from the database", t))
      0
    }
  }

  override suspend fun merge(record: Record, cacheHeaders: CacheHeaders, recordMerger: RecordMerger): Set<String> {
    return merge(records = listOf(record), cacheHeaders = cacheHeaders, recordMerger = recordMerger)
  }

  override suspend fun merge(records: Collection<Record>, cacheHeaders: CacheHeaders, recordMerger: RecordMerger): Set<String> {
    if (records.isEmpty() || cacheHeaders.headerValue(ApolloCacheHeaders.DO_NOT_STORE) == "true" || cacheHeaders.headerValue(ApolloCacheHeaders.MEMORY_CACHE_ONLY) == "true") {
      return emptySet()
    }
    return try {
      internalUpdateRecords(records = records, cacheHeaders = cacheHeaders, recordMerger = recordMerger)
    } catch (t: Throwable) {
      // Unable to merge the records in the database, it is possibly corrupted - treat this as a cache miss
      apolloExceptionHandler(Exception("Unable to merge records into the database", t))
      emptySet()
    }
  }

  override suspend fun dump(): Map<KClass<*>, Map<CacheKey, Record>> {
    recordDatabase.init()
    return mapOf(
        this::class to (recordDatabase.selectAllRecords().toList().associateBy { it.key })
            .mapValues { (_, record) -> record.withSizeInBytes(sizeOfRecord(record)) },
    )
  }

  override fun sizeOfRecord(record: Record): Int {
    val keySize = record.key.key.length
    return keySize + RecordSerializer.serialize(record).size
  }

  override suspend fun size(): Long {
    return try {
      recordDatabase.init()
      recordDatabase.databaseSize()
    } catch (t: Throwable) {
      // Unable to get the size of the database, it is possibly corrupted
      apolloExceptionHandler(Exception("Unable to get the size of the database", t))
      -1
    }
  }

  /**
   * Walks the records referenced by [keys], and the ones those reference, and so on, returning all the
   * keys reached that way.
   *
   * Each key is looked up at most once, in batches of no more than [parametersMax] — a key referenced
   * by many records, or a graph deeper than a few levels, is otherwise easy to come by.
   */
  private suspend fun getReferencedKeysRecursively(keys: Collection<String>): Set<String> {
    val referencedKeys = mutableSetOf<String>()
    val visited = mutableSetOf<String>()
    var frontier: Collection<String> = keys
    while (frontier.isNotEmpty()) {
      // Before the lookups, so that a key of this level referenced by one of its own records is not
      // looked up again on the next one.
      visited.addAll(frontier)
      val nextFrontier = mutableListOf<String>()
      for (chunkedKeys in frontier.chunked(parametersMax)) {
        for (record in recordDatabase.selectRecords(chunkedKeys)) {
          for (field in record.referencedFields()) {
            val key = field.key
            if (referencedKeys.add(key) && key !in visited) {
              nextFrontier.add(key)
            }
          }
        }
      }
      frontier = nextFrontier
    }
    return referencedKeys
  }

  /**
   * Assumes an enclosing transaction
   */
  private suspend fun internalDeleteRecords(keys: Collection<String>, cascade: Boolean): Int {
    val referencedKeys = if (cascade) {
      getReferencedKeysRecursively(keys)
    } else {
      emptySet()
    }
    return (keys + referencedKeys).chunked(parametersMax).sumOf { chunkedKeys ->
      // The statement reports how many rows it deleted, which saves asking `changes()` for it.
      recordDatabase.deleteRecords(chunkedKeys).toInt()
    }
  }

  /**
   * Updates records.
   * The [records] are merged using the given [recordMerger], requiring to load the existing records from the db first.
   */
  private suspend fun internalUpdateRecords(
      records: Collection<Record>,
      cacheHeaders: CacheHeaders,
      recordMerger: RecordMerger,
  ): Set<String> {
    recordDatabase.init()
    return if (cacheHeaders.headerValue(ApolloCacheHeaders.SKIP_MERGE) == "true") {
      // Merging has been done upstream, just insert or update the records as-is
      recordDatabase.transaction {
        for (record in records) {
          recordDatabase.insertOrUpdateRecord(record)
        }
      }
      emptySet()
    } else {
      val receivedDate = cacheHeaders.headerValue(ApolloCacheHeaders.RECEIVED_DATE)
      val expirationDate = cacheHeaders.headerValue(ApolloCacheHeaders.EXPIRATION_DATE)
      recordDatabase.transaction {
        val existingRecords = selectRecords(records.map { it.key }).associateBy { it.key }
        records.flatMap { record ->
          val record = record.withDates(receivedDate = receivedDate, expirationDate = expirationDate)
          val existingRecord = existingRecords[record.key]
          if (existingRecord == null) {
            recordDatabase.insertOrUpdateRecord(record, deleteFirst = false)
            record.fieldKeys()
          } else {
            val (mergedRecord, changedKeys) = recordMerger.merge(RecordMergerContext(existing = existingRecord, incoming = record, cacheHeaders = cacheHeaders))
            if (mergedRecord.isNotEmpty() && mergedRecord != existingRecord) {
              recordDatabase.insertOrUpdateRecord(mergedRecord)
            }
            changedKeys
          }
        }.toSet()
      }
    }
  }

  /**
   * Loads a list of records, making sure to not query more than [parametersMax] at a time
   * to help with the SQLite limitations
   */
  private suspend fun selectRecords(keys: Collection<CacheKey>): List<Record> {
    recordDatabase.init()
    val stringKeys = keys.map { it.key }
    // Reads are almost always well under the limit, and chunking one allocates a list of lists and a
    // list to flatten them back into.
    return if (stringKeys.size <= parametersMax) {
      recordDatabase.selectRecords(stringKeys)
    } else {
      stringKeys.chunked(parametersMax).flatMap { chunkedKeys ->
        recordDatabase.selectRecords(chunkedKeys)
      }
    }
  }

  override suspend fun trim(maxSizeBytes: Long, trimFactor: Float): Long {
    try {
      val size = size()
      if (size == -1L) return -1
      return if (size >= maxSizeBytes) {
        val count = recordDatabase.count()
        recordDatabase.trimByUpdatedDate((count * trimFactor).toLong())
        recordDatabase.vacuum()
        recordDatabase.databaseSize()
      } else {
        size
      }
    } catch (t: Throwable) {
      // Unable to trim the records from the database, it is possibly corrupted
      apolloExceptionHandler(Exception("Unable to trim records from the database", t))
      return -1
    }
  }

  override suspend fun close() {
    recordDatabase.close()
  }
}
