package com.apollographql.cache.normalized.sql.internal

import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.db.SqlDriver
import com.apollographql.apollo.mpp.currentTimeMillis
import com.apollographql.cache.normalized.api.Record
import com.apollographql.cache.normalized.sql.internal.record.RecordQueries
import com.apollographql.cache.normalized.sql.internal.record.SqlRecordDatabase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class RecordDatabase(
    private val driver: SqlDriver,
    private val name: String?,
) {
  private val recordQueries: RecordQueries = SqlRecordDatabase(driver).recordQueries

  private val mutex = Mutex()
  private var isInitialized = false

  suspend fun init() {
    if (isInitialized) return
    mutex.withLock {
      if (isInitialized) return@withLock
      if (name != null) bind(name)
      maybeCreateOrMigrateSchema(driver)
      checkSchema(driver)

      // Increase the memory cache to 8 MiB
      // https://www.sqlite.org/pragma.html#pragma_cache_size
      recordQueries.setCacheSize()
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
    return recordQueries.selectRecords(keys).awaitAsList().map { RecordSerializer.deserialize(it.key, it.record) }
  }

  suspend fun selectAllRecords(): List<Record> {
    return recordQueries.selectAllRecords().awaitAsList().map { RecordSerializer.deserialize(it.key, it.record) }
  }

  suspend fun insertOrUpdateRecord(record: Record) {
    recordQueries.insertOrUpdateRecord(key = record.key.key, record = RecordSerializer.serialize(record), updated_date = currentTimeMillis())
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
    if (name != null) release(name)
  }

  companion object {
    private val mutex = Mutex()
    private val boundNames = mutableSetOf<String>()

    suspend fun bind(name: String) {
      mutex.withLock {
        check(!boundNames.contains(name)) { "The file $name is already bound to another SqlNormalizedCache. Call SqlNormalizedCache.close() to release it." }
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
