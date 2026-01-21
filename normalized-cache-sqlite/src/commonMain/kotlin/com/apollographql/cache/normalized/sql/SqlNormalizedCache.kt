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
    if (cacheHeaders.hasHeader(ApolloCacheHeaders.MEMORY_CACHE_ONLY)) {
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
    if (cacheHeaders.hasHeader(ApolloCacheHeaders.DO_NOT_STORE) || cacheHeaders.hasHeader(ApolloCacheHeaders.MEMORY_CACHE_ONLY)) {
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

  private suspend fun getReferencedKeysRecursively(
      keys: Collection<String>,
      visited: MutableSet<String> = mutableSetOf(),
  ): Set<String> {
    if (keys.isEmpty()) return emptySet()
    val recordsToSelect = keys - visited
    val referencedKeys = if (recordsToSelect.isEmpty()) {
      emptySet()
    } else {
      recordDatabase.selectRecords(recordsToSelect).flatMap { it.referencedFields() }.map { it.key }.toSet()
    }
    visited += keys
    return referencedKeys + getReferencedKeysRecursively(referencedKeys, visited)
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
      recordDatabase.deleteRecords(chunkedKeys)
      recordDatabase.changes().toInt()
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
          val existingRecord = existingRecords[record.key]
          if (existingRecord == null) {
            recordDatabase.insertOrUpdateRecord(record.withDates(receivedDate = receivedDate, expirationDate = expirationDate))
            record.fieldKeys()
          } else {
            val (mergedRecord, changedKeys) = recordMerger.merge(RecordMergerContext(existing = existingRecord, incoming = record, cacheHeaders = cacheHeaders))
            if (mergedRecord.isNotEmpty()) {
              recordDatabase.insertOrUpdateRecord(mergedRecord.withDates(receivedDate = receivedDate, expirationDate = expirationDate))
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
    return keys
        .map { it.key }
        .chunked(parametersMax).flatMap { chunkedKeys ->
          recordDatabase.selectRecords(chunkedKeys)
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
