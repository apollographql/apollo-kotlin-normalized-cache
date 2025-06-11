package com.apollographql.cache.normalized.sql.internal

import app.cash.sqldelight.Query
import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import com.apollographql.apollo.exception.apolloExceptionHandler

internal suspend fun checkSchema(driver: SqlDriver) {
  val tableNames = mutableListOf<String>()
  try {
    // https://sqlite.org/forum/info/d90adfbb0a6eea88
    // The name is sqlite_schema these days but older versions use sqlite_master and sqlite_master is recognized everywhere so use that
    executeQuery(driver, "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name;") { cursor ->
      cursor.getString(0)!!
    }.awaitAsList().forEach { tableName ->
      tableNames.add(tableName)
    }
  } catch (e: Exception) {
    apolloExceptionHandler(Exception("An exception occurred while looking up the table names", e))
    /**
     * Best effort: if we can't find any table, open the DB anyway and let's see what's happening
     */
  }

  val expectedTableName = "record"
  check(tableNames.isEmpty() || tableNames.contains(expectedTableName)) {
    "Apollo: Cannot find the '$expectedTableName' table (found '$tableNames' instead)"
  }
}

internal fun <T : Any> executeQuery(
    driver: SqlDriver,
    sql: String,
    mapper: (cursor: SqlCursor) -> T,
): Query<T> {
  return Query(sql.hashCode(), arrayOf(), driver, "", "", sql) { cursor ->
    mapper(cursor)
  }
}
