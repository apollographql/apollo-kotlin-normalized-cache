package com.apollographql.cache.normalized.sql.internal

import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
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

/**
 * Assembles records out of the rows of the `record` table, whose chunks come contiguously and in chunk
 * index order.
 *
 * Only a record serializing to more than [BLOB_CHUNK_SIZE] spans several rows, which is rare enough
 * that the first chunk is kept as it came out of the cursor and copied into the buffer only if a
 * second one turns up: a record that fits in a single row is deserialized without being copied through
 * the buffer at all.
 */
private class RecordChunks {
  private val buffer = Buffer()
  private var key: String? = null
  private var firstChunk: ByteArray? = null

  /**
   * Adds a row, returning the record it completes, if it starts a new one.
   */
  fun add(key: String, chunk: ByteArray): Record? {
    if (key == this.key) {
      firstChunk?.let {
        buffer.write(it)
        firstChunk = null
      }
      buffer.write(chunk)
      return null
    }
    return takeRecord().also {
      this.key = key
      firstChunk = chunk
    }
  }

  /**
   * Returns the record accumulated so far, if any, and forgets it.
   */
  fun takeRecord(): Record? {
    val key = key ?: return null
    val firstChunk = firstChunk
    this.key = null
    this.firstChunk = null
    return if (firstChunk != null) {
      RecordSerializer.deserialize(key, firstChunk)
    } else {
      // Leaves the buffer empty, ready for the next record.
      RecordSerializer.deserialize(key, buffer.readByteArray())
    }
  }
}

/**
 * Rounds a number of `IN` parameters up to the next power of two, capped at [parametersMax].
 *
 * A statement is compiled once per SQL string, and an `IN` list of a given length is its own string,
 * so keeping to a handful of lengths keeps SQLite from parsing and compiling the same query again for
 * every call that happens to pass a different number of keys. The list is filled up by repeating a
 * key, which `IN` ignores, in exchange for binding up to twice as many parameters as there are keys.
 */
private fun paddedParameterCount(count: Int): Int {
  var padded = 1
  while (padded < count) {
    padded = padded shl 1
  }
  return minOf(padded, parametersMax)
}

private fun bindParameters(count: Int): String {
  return StringBuilder(2 * count + 1).apply {
    append('(')
    repeat(count) {
      append(if (it == 0) "?" else ",?")
    }
    append(')')
  }.toString()
}

private fun SqlPreparedStatement.bindKeys(keys: Collection<String>, parameterCount: Int) {
  var index = 0
  var lastKey: String? = null
  for (key in keys) {
    bindString(index++, key)
    lastKey = key
  }
  while (index < parameterCount) {
    bindString(index++, lastKey)
  }
}

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
    if (keys.isEmpty()) return emptyList()
    val parameterCount = paddedParameterCount(keys.size)
    val sql = "SELECT key, record FROM record WHERE key IN ${bindParameters(parameterCount)} ORDER BY key, chunk_index"
    return driver.executeQuery(
        identifier = sql.hashCode(),
        sql = sql,
        parameters = parameterCount,
        binders = { bindKeys(keys, parameterCount) },
        // The rows are read straight into records: collecting them into a list first allocates an object
        // per chunk, and a list to hold them, for a result that is usually one record per row. The two
        // branches are what doing that by hand costs — a synchronous driver invalidates the cursor as
        // soon as the mapper returns, so its rows must be read without suspending, which is also how
        // `awaitAsList` goes about it.
        mapper = { cursor ->
          val records = ArrayList<Record>(keys.size)
          val chunks = RecordChunks()
          fun readRow() {
            chunks.add(key = cursor.getString(0)!!, chunk = cursor.getBytes(1)!!)?.let { records.add(it) }
          }
          when (val hasFirstRow = cursor.next()) {
            is QueryResult.AsyncValue -> QueryResult.AsyncValue {
              if (hasFirstRow.await()) {
                readRow()
                while (cursor.next().await()) readRow()
              }
              chunks.takeRecord()?.let { records.add(it) }
              records
            }

            is QueryResult.Value -> {
              if (hasFirstRow.value) {
                readRow()
                while (cursor.next().value) readRow()
              }
              chunks.takeRecord()?.let { records.add(it) }
              QueryResult.Value(records)
            }
          }
        },
    ).await()
  }

  fun selectAllRecords(pageSize: Long = 100): Flow<Record> {
    return flow {
      val chunks = RecordChunks()
      // Sorts before any row, so the first page starts at the first one.
      var lastKey = ""
      var lastChunkIndex = -1L
      while (true) {
        val rowPage = recordQueries.selectRecordsFrom(
            lastKey = lastKey,
            lastChunkIndex = lastChunkIndex,
            limit = pageSize,
        ).awaitAsList()
        for (row in rowPage) {
          chunks.add(key = row.key, chunk = row.record)?.let { emit(it) }
          lastKey = row.key
          lastChunkIndex = row.chunk_index
        }

        if (rowPage.size < pageSize) {
          chunks.takeRecord()?.let { emit(it) }
          break
        }
      }
    }
  }

  /**
   * Deserializes the record accumulated in this buffer and leaves the buffer empty, ready for the
   * next one.
   *
   * [RecordSerializer.deserialize] consumes exactly the bytes it wrote, but the buffer is reused
   * across records, so it is cleared explicitly: were a record ever to deserialize short, the
   * leftovers would corrupt every record read after it.
   */
  private fun Buffer.readRecord(key: String): Record {
    return try {
      RecordSerializer.deserialize(key, this)
    } finally {
      clear()
    }
  }

  /**
   * Must be called inside a transaction.
   */
  suspend fun insertOrUpdateRecord(record: Record, deleteFirst: Boolean = true) {
    if (deleteFirst) recordQueries.deleteRecordByKey(record.key.key)
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

    // Slice the array directly: `asIterable().chunked()` would box every byte of the record into a
    // `List<Byte>` and materialize all the chunks before the first insert, only to unbox them again.
    var index = 0
    var offset = 0
    while (offset < recordBytes.size) {
      val end = minOf(offset + BLOB_CHUNK_SIZE, recordBytes.size)
      recordQueries.insertOrUpdateRecord(
          key = record.key.key,
          chunk_index = index.toLong(),
          record = recordBytes.copyOfRange(offset, end),
          updated_date = updatedDate,
      )
      index++
      offset = end
    }
  }

  /**
   * @param keys the keys of the records to delete, size must be <= [parametersMax]
   * @return the number of rows deleted
   */
  suspend fun deleteRecords(keys: Collection<String>): Long {
    if (keys.isEmpty()) return 0
    if (keys.size == 1) return recordQueries.deleteRecordByKey(keys.first())
    val parameterCount = paddedParameterCount(keys.size)
    val sql = "DELETE FROM record WHERE key IN ${bindParameters(parameterCount)}"
    return driver.execute(
        identifier = sql.hashCode(),
        sql = sql,
        parameters = parameterCount,
        binders = { bindKeys(keys, parameterCount) },
    ).await() + changes(recordQueries)
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

/**
 * On JS, [SqlDriver.execute] does not return the number of rows changed, so we must execute `changes`.
 * On other platforms, this returns 0 and saves one query.
 */
internal expect suspend fun changes(recordQueries: RecordQueries): Long
