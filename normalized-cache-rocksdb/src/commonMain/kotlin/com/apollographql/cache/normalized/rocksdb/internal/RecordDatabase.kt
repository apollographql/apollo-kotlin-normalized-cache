package com.apollographql.cache.normalized.rocksdb.internal

import com.apollographql.cache.normalized.api.Record
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import maryk.rocksdb.RocksDB
import maryk.rocksdb.WriteBatch
import maryk.rocksdb.WriteOptions
import maryk.rocksdb.openRocksDB

internal class RecordDatabase(
    private val name: String,
) {
  private val mutex = Mutex()
  private var isInitialized = false

  private lateinit var db: RocksDB


  suspend fun init() {
    if (isInitialized) return
    mutex.withLock {
      if (isInitialized) return
      checkNotBound(name)
      db = openRocksDB(name)
      bind(name)
      isInitialized = true
    }
  }

  fun selectRecords(keys: Collection<String>): List<Record> {
    return db.multiGetAsList(keys.map { it.encodeToByteArray() }).mapIndexedNotNull { index, value ->
      if (value != null) {
        RecordSerializer.deserialize(keys.elementAt(index), value)
      } else {
        null
      }
    }
  }

  fun selectAllRecords(): Flow<Record> {
    return flow {
      db.newIterator().use { iterator ->
        iterator.seekToFirst()
        while (iterator.isValid()) {
          emit(RecordSerializer.deserialize(iterator.key().decodeToString(), iterator.value()))
          iterator.next()
        }
      }
    }
  }

  fun insertOrUpdateRecords(records: Collection<Record>) {
    WriteBatch().use { batch ->
      for (record in records) {
        batch.put(record.key.key.encodeToByteArray(), RecordSerializer.serialize(record))
      }
      WriteOptions().use { writeOptions ->
        db.write(writeOptions, batch)
      }
    }
  }

  /**
   * @param keys the keys of the records to delete, size must be <= [parametersMax]
   */
  fun deleteRecords(keys: Collection<String>) {
    WriteBatch().use { batch ->
      for (key in keys) {
        batch.delete(key.encodeToByteArray())
      }
      WriteOptions().use { writeOptions ->
        db.write(writeOptions, batch)
      }
    }
  }

  fun deleteAllRecords() {
    // TODO just delete the file and recreate it
    WriteBatch().use { batch ->
      db.newIterator().use { iterator ->
        iterator.seekToFirst()
        while (iterator.isValid()) {
          batch.delete(iterator.key())
          iterator.next()
        }
      }
      WriteOptions().use { writeOptions ->
        db.write(writeOptions, batch)
      }
    }
  }

  fun databaseSize(): Long {
    // TODO
    return -1
  }

  fun count(): Long {
    // TODO
    return -1
  }

  fun trimByUpdatedDate(limit: Long) {
    // TODO
  }

  suspend fun close() {
    if (!isInitialized) return
    db.close()
    release(name)
  }

  companion object {
    private val mutex = Mutex()
    private val boundNames = mutableSetOf<String>()

    suspend fun checkNotBound(name: String) {
      mutex.withLock {
        check(!boundNames.contains(name)) { "The file $name is already bound to another RocksDBNormalizedCache. Call RocksDBNormalizedCache.close() to release it." }
      }
    }

    suspend fun bind(name: String) {
      mutex.withLock {
        boundNames.add(name)
      }
    }

    suspend fun release(name: String) {
      mutex.withLock {
        boundNames.remove(name)
      }
    }
  }
}
