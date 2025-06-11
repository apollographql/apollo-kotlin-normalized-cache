package com.apollographql.cache.normalized.sql.internal

import app.cash.sqldelight.db.SqlDriver

/**
 * Some implementations like Native and Android take the schema when creating the driver and the driver
 * will take care of migrations
 *
 * Others like JVM don't do this automatically. This is when [maybeCreateOrMigrateSchema] is needed
 */
internal expect suspend fun maybeCreateOrMigrateSchema(driver: SqlDriver)

// See https://www.sqlite.org/limits.html#:~:text=Maximum%20Number%20Of%20Host%20Parameters
internal expect val parametersMax: Int
