package com.apollographql.cache.normalized.disk

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
import com.apollographql.cache.normalized.disk.internal.RecordSerializer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM
import kotlin.reflect.KClass

class DiskNormalizedCache internal constructor(path: String) : NormalizedCache {
  private val rootPath = path.toPath()

  override suspend fun loadRecord(key: CacheKey, cacheHeaders: CacheHeaders): Record? {
    if (cacheHeaders.hasHeader(ApolloCacheHeaders.MEMORY_CACHE_ONLY)) {
      return null
    }
    val keyStr = key.key
    return selectRecord(path = rootPath / keyStr.escapePath(), key = keyStr)
  }

  override suspend fun loadRecords(keys: Collection<CacheKey>, cacheHeaders: CacheHeaders): Collection<Record> {
    if (cacheHeaders.hasHeader(ApolloCacheHeaders.MEMORY_CACHE_ONLY)) {
      return emptyList()
    }
    return selectRecords(keys.map { it.key })
  }

  override suspend fun loadAllRecords(): Flow<Record> {
    return flow {
      for (path in FileSystem.SYSTEM.list(rootPath)) {
        val key = path.name.unescapePath()
        emit(selectRecord(path = path, key = key)!!)
      }
    }
  }

  override suspend fun clearAll() {
    FileSystem.SYSTEM.deleteRecursively(rootPath)
  }

  override suspend fun remove(cacheKey: CacheKey, cascade: Boolean): Boolean {
    return remove(cacheKeys = listOf(cacheKey), cascade = cascade) > 0
  }

  override suspend fun remove(cacheKeys: Collection<CacheKey>, cascade: Boolean): Int {
    return internalDeleteRecords(cacheKeys.map { it.key }, cascade)
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
    } catch (e: Exception) {
      // Unable to merge the records in the database, it is possibly corrupted - treat this as a cache miss
      apolloExceptionHandler(Exception("Unable to merge records into the database", e))
      emptySet()
    }
  }

  override suspend fun dump(): Map<KClass<*>, Map<CacheKey, Record>> {
    return mapOf(
        this::class to (loadAllRecords().toList().associateBy { it.key })
            .mapValues { (_, record) -> record.withSizeInBytes(sizeOfRecord(record)) },
    )
  }

  override fun sizeOfRecord(record: Record): Int {
    val keySize = record.key.key.length
    return keySize + RecordSerializer.serialize(record).size
  }

  override suspend fun size(): Long {
    return FileSystem.SYSTEM.list(rootPath).sumOf { path -> FileSystem.SYSTEM.metadataOrNull(path)?.size ?: 0L }
  }

  private fun getReferencedKeysRecursively(
      keys: Collection<String>,
      visited: MutableSet<String> = mutableSetOf(),
  ): Set<String> {
    if (keys.isEmpty()) return emptySet()
    val referencedKeys =
      selectRecords(keys - visited).flatMap { it.referencedFields() }.map { it.key }.toSet()
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
    return (keys + referencedKeys).sumOf { key ->
      FileSystem.SYSTEM.delete(rootPath / key)
      1L
    }.toInt()
  }

  /**
   * Updates records.
   * The [records] are merged using the given [recordMerger], requiring to load the existing records from the disk first.
   */
  private suspend fun internalUpdateRecords(
      records: Collection<Record>,
      cacheHeaders: CacheHeaders,
      recordMerger: RecordMerger,
  ): Set<String> {
    return if (cacheHeaders.headerValue(ApolloCacheHeaders.SKIP_MERGE) == "true") {
      // Merging has been done upstream, just insert or update the records as-is
      for (record in records) {
        insertOrUpdateRecord(record)
      }
      emptySet()
    } else {
      val receivedDate = cacheHeaders.headerValue(ApolloCacheHeaders.RECEIVED_DATE)
      val expirationDate = cacheHeaders.headerValue(ApolloCacheHeaders.EXPIRATION_DATE)
      val existingRecords = selectRecords(records.map { it.key.key }).associateBy { it.key }
      records.flatMap { record ->
        val existingRecord = existingRecords[record.key]
        if (existingRecord == null) {
          insertOrUpdateRecord(record.withDates(receivedDate = receivedDate, expirationDate = expirationDate))
          record.fieldKeys()
        } else {
          val (mergedRecord, changedKeys) = recordMerger.merge(RecordMergerContext(existing = existingRecord, incoming = record, cacheHeaders = cacheHeaders))
          if (mergedRecord.isNotEmpty()) {
            insertOrUpdateRecord(mergedRecord.withDates(receivedDate = receivedDate, expirationDate = expirationDate))
          }
          changedKeys
        }
      }.toSet()
    }
  }

  private fun insertOrUpdateRecord(record: Record) {
    val path = rootPath / record.key.key.escapePath()
    FileSystem.SYSTEM.createDirectories(rootPath)
    FileSystem.SYSTEM.write(path) {
      write(RecordSerializer.serialize(record))
    }
  }

  private fun selectRecords(keys: Collection<String>): List<Record> {
    return keys.mapNotNull {
      val path = rootPath / it.escapePath()
      selectRecord(path = path, key = it)
    }
  }

  private fun selectRecord(path: Path, key: String): Record? {
    return if (!FileSystem.SYSTEM.exists(path)) {
      null
    } else {
      RecordSerializer.deserialize(key, FileSystem.SYSTEM.read(path) { readByteArray() })
    }
  }

  override suspend fun trim(maxSizeBytes: Long, trimFactor: Float): Long {
    // TODO
    return -1L
  }
}

/**
 * - `:` is replaced by | since it is a common character in cache keys but not allowed in file names
 * - other special characters are percent-encoded
 */
private fun String.escapePath(): String {
  return buildString {
    for (char in this@escapePath) {
      when (char) {
        ':' -> append('|')
        '|', '\\', '/', '?', '%', '*', '"', '<', '>' -> {
          append('%')
          append(char.code.toString(16).padStart(2, '0'))
        }

        else -> append(char)
      }
    }
  }
}

private fun String.unescapePath(): String {
  return buildString {
    var i = 0
    while (i < this@unescapePath.length) {
      when (val char = this@unescapePath[i]) {
        '|' -> {
          append(':')
          i++
        }

        '%' -> {
          val hex = this@unescapePath.substring(i + 1, i + 3)
          val decodedChar = hex.toInt(16).toChar()
          append(decodedChar)
          i += 3
        }

        else -> {
          append(char)
          i++
        }
      }
    }
  }
}

