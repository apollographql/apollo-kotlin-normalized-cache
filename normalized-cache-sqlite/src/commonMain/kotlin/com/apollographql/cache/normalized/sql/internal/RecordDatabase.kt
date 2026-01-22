package com.apollographql.cache.normalized.sql.internal

import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.db.SqlDriver
import com.apollographql.apollo.mpp.currentTimeMillis
import com.apollographql.cache.normalized.api.Record
import com.apollographql.cache.normalized.sql.internal.record.RecordQueries
import com.apollographql.cache.normalized.sql.internal.record.SqlRecordDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.Buffer

private const val BLOB_CHUNK_SIZE = 1024 * 1024 // 1 MiB

internal class RecordDatabase(
    private val driver: SqlDriver,
    private val name: String?,
) {
  private val mutex = Mutex()
  private var isInitialized = false

  private lateinit var recordQueries: RecordQueries

  suspend fun init() {
    if (isInitialized) return
    mutex.withLock {
      if (isInitialized) return
      if (name != null) checkNotBound(name)
      recordQueries = SqlRecordDatabase(driver).recordQueries
      maybeCreateOrMigrateSchema(driver)
      checkSchema(driver)

      // Increase the memory cache to 8 MiB
      // https://www.sqlite.org/pragma.html#pragma_cache_size
      recordQueries.setCacheSize()
      if (name != null) bind(name)
      isInitialized = true
    }
  }

  suspend fun <T> transaction(body: suspend () -> T): T {
    return recordQueries.transactionWithResult {
      body()
    }
  }

  /**
   * @param keys the keys of the records to select, size must be <= [parametersMax]
   */
  suspend fun selectRecords(keys: Collection<String>): List<Record> {
    val rows = recordQueries.selectRecords(keys).awaitAsList()
    val records = ArrayList<Record>(rows.size)
    val buffer = Buffer()
    var lastKey: String? = null
    for (row in rows) {
      val key = row.key
      if (key != lastKey && lastKey != null) {
        records.add(RecordSerializer.deserialize(lastKey, buffer.readByteArray()))
      }
      buffer.write(row.record)
      lastKey = key
    }
    if (lastKey != null) {
      records.add(RecordSerializer.deserialize(lastKey, buffer.readByteArray()))
    }
    return records
  }

  fun selectAllRecords(pageSize: Long = 100): Flow<Record> {
    return flow {
      var offset = 0L
      val buffer = Buffer()
      var lastKey: String? = null
      while (true) {
        val rowPage = recordQueries.selectAllRecords(limit = pageSize, offset = offset).awaitAsList()
        for (row in rowPage) {
          val key = row.key
          if (key != lastKey && lastKey != null) {
            emit(RecordSerializer.deserialize(lastKey, buffer.readByteArray()))
          }
          buffer.write(row.record)
          lastKey = key
        }

        if (rowPage.size < pageSize) {
          if (lastKey != null) {
            emit(RecordSerializer.deserialize(lastKey, buffer.readByteArray()))
          }
          break
        }
        offset += pageSize
      }
    }
  }

  /**
   * Must be called inside a transaction.
   */
  suspend fun insertOrUpdateRecord(record: Record) {
    recordQueries.deleteRecords(listOf(record.key.key))
    val recordBytes = RecordSerializer.serialize(record)
    val updatedDate = currentTimeMillis()
    // Fast path for small records
    if (recordBytes.size <= BLOB_CHUNK_SIZE) {
      recordQueries.insertOrUpdateRecord(
          key = record.key.key,
          chunk_index = 0L,
          record = recordBytes,
          updated_date = updatedDate,
      )
      return
    }

    val chunks = recordBytes.asIterable().chunked(BLOB_CHUNK_SIZE)
    for ((index, chunk) in chunks.withIndex()) {
      recordQueries.insertOrUpdateRecord(
          key = record.key.key,
          chunk_index = index.toLong(),
          record = chunk.toByteArray(),
          updated_date = updatedDate,
      )
    }
  }

  /**
   * @param keys the keys of the records to delete, size must be <= [parametersMax]
   */
  suspend fun deleteRecords(keys: Collection<String>) {
    recordQueries.deleteRecords(keys)
  }

  suspend fun deleteAllRecords() {
    recordQueries.deleteAllRecords()
  }

  suspend fun databaseSize(): Long {
    return executeQuery(
        driver = driver,
        sql = "SELECT page_count * page_size FROM pragma_page_count(), pragma_page_size();",
        mapper = {
          it.getLong(0)!!
        },
    ).awaitAsOne()
  }

  suspend fun count(): Long {
    return recordQueries.count().awaitAsOne()
  }

  suspend fun trimByUpdatedDate(limit: Long) {
    recordQueries.trimByUpdatedDate(limit)
  }

  suspend fun vacuum() {
    driver.await(null, "VACUUM", 0)
  }

  suspend fun changes(): Long {
    return recordQueries.changes().awaitAsOne()
  }

  suspend fun close() {
    if (!isInitialized) return
    driver.close()
    if (name != null) release(name)
  }

  companion object {
    private val mutex = Mutex()
    private val boundNames = mutableSetOf<String>()

    suspend fun checkNotBound(name: String) {
      mutex.withLock {
        check(!boundNames.contains(name)) { "The file $name is already bound to another SqlNormalizedCache. Call SqlNormalizedCache.close() to release it." }
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
