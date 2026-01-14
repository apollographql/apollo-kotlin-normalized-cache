package com.apollographql.cache.normalized.sql.bundled.internal

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.apollographql.apollo.mpp.currentTimeMillis
import com.apollographql.cache.normalized.api.Record
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class RecordDatabase(
    private val name: String?,
) {
  private lateinit var connection: SQLiteConnection

  private val mutex = Mutex()
  private var isInitialized = false

  suspend fun init() {
    if (isInitialized) return
    withLock {
      if (isInitialized) return@withLock
      connection = BundledSQLiteDriver().open(name ?: ":memory:")
      maybeCreateOrMigrateSchema()

      // Increase the memory cache to 8 MiB
      // https://www.sqlite.org/pragma.html#pragma_cache_size
      connection.execSQL("""PRAGMA cache_size = -8192""")
      if (name != null) bind(name)
      isInitialized = true
    }
  }

  private suspend fun <T> withLock(block: suspend () -> T): T {
    return mutex.withReentrantLock { block() }
  }

  private fun maybeCreateOrMigrateSchema() {
    connection.execSQL(
        //language=SQL
        """
        CREATE TABLE IF NOT EXISTS record (
          key TEXT NOT NULL,
          record BLOB NOT NULL,
          updated_date INTEGER NOT NULL,
          PRIMARY KEY (key) ON CONFLICT REPLACE
        )
        WITHOUT ROWID;
        """.trimIndent(),
    )
  }

  suspend fun <T> transaction(body: suspend () -> T): T = withLock {
    connection.execSQL("BEGIN IMMEDIATE TRANSACTION")
    try {
      val result = body()
      connection.execSQL("END TRANSACTION")
      return@withLock result
    } catch (t: Throwable) {
      connection.execSQL("ROLLBACK TRANSACTION")
      throw t
    }
  }

  /**
   * @param keys the keys of the records to select, size must be <= [parametersMax]
   */
  suspend fun selectRecords(keys: Collection<String>): List<Record> = withLock {
    val inClause = keys.joinToString(separator = ",") { "?" }
    // TODO reuse prepared statements
    connection.prepare("SELECT key, record FROM record WHERE key IN ($inClause)").use { statement ->
      for ((index, key) in keys.withIndex()) {
        statement.bindText(index + 1, key)
      }
      buildList {
        while (statement.step()) {
          add(
              RecordSerializer.deserialize(
                  key = statement.getText(0),
                  bytes = statement.getBlob(1),
              ),
          )
        }
      }
    }
  }

  fun selectAllRecords(): Flow<Record> {
    return flow {
      // TODO reuse prepared statements
      withLock {
        connection.prepare("SELECT key, record FROM record").use { statement ->
          while (statement.step()) {
            val record = RecordSerializer.deserialize(
                key = statement.getText(0),
                bytes = statement.getBlob(1),
            )
            emit(record)
          }
        }
      }
    }
  }

  suspend fun insertOrUpdateRecord(record: Record) = withLock {
    connection.prepare("INSERT INTO record (key, record, updated_date) VALUES (?, ?, ?)").use { statement ->
      statement.bindText(1, record.key.key)
      statement.bindBlob(2, RecordSerializer.serialize(record))
      statement.bindLong(3, currentTimeMillis())
      statement.step()
    }
  }


  /**
   * @param keys the keys of the records to delete, size must be <= [parametersMax]
   */
  suspend fun deleteRecords(keys: Collection<String>) = withLock {
    val inClause = keys.joinToString(separator = ",") { "?" }
    connection.prepare("DELETE FROM record WHERE key IN ($inClause)").use { statement ->
      for ((index, key) in keys.withIndex()) {
        statement.bindText(index + 1, key)
      }
      statement.step()
    }
  }

  suspend fun deleteAllRecords() = withLock {
    connection.execSQL("DELETE FROM record")
  }

  suspend fun databaseSize(): Long = withLock {
    connection.prepare("SELECT page_count * page_size FROM pragma_page_count(), pragma_page_size()").use { statement ->
      statement.step()
      statement.getLong(0)
    }
  }

  suspend fun count(): Long = withLock {
    connection.prepare("SELECT count(*) FROM record").use { statement ->
      statement.step()
      statement.getLong(0)
    }
  }

  suspend fun trimByUpdatedDate(limit: Long) = withLock {
    connection.prepare("DELETE FROM record WHERE key IN (SELECT key FROM record ORDER BY updated_date LIMIT ?)").use { statement ->
      statement.bindLong(1, limit)
      statement.step()
    }
  }

  suspend fun vacuum() = withLock {
    connection.execSQL("VACUUM")
  }

  suspend fun changes(): Long = withLock {
    connection.prepare("SELECT changes()").use { statement ->
      statement.step()
      statement.getLong(0)
    }
  }

  suspend fun close() = withLock {
    if (name != null) release(name)
    connection.close()
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

internal const val parametersMax = 32766
