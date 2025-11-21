package com.apollographql.cache.normalized.sql

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.apollographql.cache.normalized.api.NormalizedCacheFactory
import java.io.File
import java.util.Properties

/**
 * @param url Database connection URL in the form of `jdbc:sqlite:path` where `path` is either blank
 * (creating an in-memory database) or a path to a file.
 * @param properties
 */
fun SqlNormalizedCacheFactory(
    url: String,
    properties: Properties,
): NormalizedCacheFactory = SqlNormalizedCacheFactory(JdbcSqliteDriver(url, properties), name = null)

/**
 * @param name the name of the database or null for an in-memory database
 * @param baseDir the baseDirectory where to store the database.
 * If [baseDir] does not exist, it will be created
 * If [baseDir] is a relative path, it will be interpreted relative to the current working directory
 */
fun SqlNormalizedCacheFactory(
    name: String?,
    baseDir: String?,
): NormalizedCacheFactory = SqlNormalizedCacheFactory(JdbcSqliteDriver(name.toUrl(baseDir), Properties()), name = name)

actual fun SqlNormalizedCacheFactory(name: String?): NormalizedCacheFactory = SqlNormalizedCacheFactory(name, null)


private fun String?.toUrl(baseDir: String?): String {
  return if (this == null) {
    JdbcSqliteDriver.IN_MEMORY
  } else {
    val dir = baseDir?.let { File(it) } ?: File(System.getProperty("user.home")).resolve(".apollo")
    dir.mkdirs()
    "${JdbcSqliteDriver.IN_MEMORY}${dir.resolve(this).absolutePath}"
  }
}

