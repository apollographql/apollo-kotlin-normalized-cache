package com.apollographql.cache.normalized.sql.internal

import app.cash.sqldelight.async.coroutines.awaitAsOne
import com.apollographql.cache.normalized.sql.internal.record.RecordQueries

internal actual suspend fun changes(recordQueries: RecordQueries): Long {
  return recordQueries.changes().awaitAsOne()
}
