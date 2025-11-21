package com.apollographql.cache.normalized.sql

import app.cash.sqldelight.driver.worker.createDefaultWebWorkerDriver
import com.apollographql.cache.normalized.api.NormalizedCacheFactory

/**
 * Returns a SqlNormalizedCacheFactory configured with the default driver which works with SQL.js.
 * For this to work you must have these dependencies in your project:
 *
 * ```
 * implementation(npm("@cashapp/sqldelight-sqljs-worker", "2.1.0"))
 * implementation(npm("sql.js", "1.8.0"))
 * ```
 *
 * See [the SQLDelight documentation](https://sqldelight.github.io/sqldelight/2.1.0/js_sqlite/sqljs_worker/).
 */
actual fun SqlNormalizedCacheFactory(name: String?): NormalizedCacheFactory {
  return SqlNormalizedCacheFactory(createDefaultWebWorkerDriver(), name = null)
}

