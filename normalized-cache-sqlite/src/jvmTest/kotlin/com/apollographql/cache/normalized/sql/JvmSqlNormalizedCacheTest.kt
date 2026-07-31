package com.apollographql.cache.normalized.sql

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.apollographql.cache.normalized.api.CacheHeaders
import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.api.DefaultRecordMerger
import com.apollographql.cache.normalized.api.Record
import com.apollographql.cache.normalized.sql.internal.RecordDatabase
import com.apollographql.cache.normalized.testing.runTest
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals

class JvmSqlNormalizedCacheTest {
  @Test
  fun mergingIdenticalRecordIsANoOp() = runTest {
    val driver = SpyDriver(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties()))
    val cache = SqlNormalizedCache(RecordDatabase(driver, null))
    // Initial insert
    cache.merge(
        record = Record(
            key = CacheKey.QUERY_ROOT,
            fields = mapOf(
                "a" to "0",
            ),
        ),
        cacheHeaders = CacheHeaders.NONE,
        recordMerger = DefaultRecordMerger,
    )
    var count = driver.count

    // Insert the same record
    cache.merge(
        record = Record(
            key = CacheKey.QUERY_ROOT,
            fields = mapOf(
                "a" to "0",
            ),
        ),
        cacheHeaders = CacheHeaders.NONE,
        recordMerger = DefaultRecordMerger,
    )

    // Expected 1 select, 0 insert
    assertEquals(1, driver.count - count)
    count = driver.count

    // Insert a different record
    cache.merge(
        record = Record(
            key = CacheKey.QUERY_ROOT,
            fields = mapOf(
                "a" to "1",
            ),
        ),
        cacheHeaders = CacheHeaders.NONE,
        recordMerger = DefaultRecordMerger,
    )

    // Expected 1 select, 1 delete, 1 insert
    assertEquals(3, driver.count - count)

  }
}

private class SpyDriver(private val delegate: SqlDriver) : SqlDriver by delegate {
  var count = 0

  override fun execute(
      identifier: Int?,
      sql: String,
      parameters: Int,
      binders: (SqlPreparedStatement.() -> Unit)?,
  ): QueryResult<Long> {
    count++
    return delegate.execute(identifier, sql, parameters, binders)
  }

  override fun <R> executeQuery(
      identifier: Int?,
      sql: String,
      mapper: (SqlCursor) -> QueryResult<R>,
      parameters: Int,
      binders: (SqlPreparedStatement.() -> Unit)?,
  ): QueryResult<R> {
    count++
    return delegate.executeQuery(identifier, sql, mapper, parameters, binders)
  }
}
