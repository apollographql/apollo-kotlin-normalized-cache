package com.apollographql.cache.normalized.sql.internal

import com.apollographql.cache.normalized.sql.internal.record.RecordQueries

internal actual suspend fun changes(recordQueries: RecordQueries): Long {
  return 0L
}
