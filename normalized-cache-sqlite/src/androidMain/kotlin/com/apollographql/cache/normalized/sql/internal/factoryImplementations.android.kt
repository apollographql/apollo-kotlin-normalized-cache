package com.apollographql.cache.normalized.sql.internal

import android.os.Build
import app.cash.sqldelight.db.SqlDriver

internal actual suspend fun maybeCreateOrMigrateSchema(driver: SqlDriver) {
  // no op
}

// See https://www.sqlite.org/limits.html#:~:text=Maximum%20Number%20Of%20Host%20Parameters
// and https://developer.android.com/reference/android/database/sqlite/package-summary.html
internal actual val parametersMax: Int = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
  999
} else {
  32766
}
