package com.apollographql.cache.normalized.sql

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.apollographql.cache.normalized.api.NormalizedCacheFactory
import com.apollographql.cache.normalized.sql.internal.record.SqlRecordDatabase

actual fun SqlNormalizedCacheFactory(name: String?): NormalizedCacheFactory =
  SqlNormalizedCacheFactory(createDriver(name))

/**
 * @param name Name of the database file in the cache directory, or an absolute path to a file, or null for an in-memory database
 * (as per Android framework implementation).
 * @param factory Factory class to create instances of [SupportSQLiteOpenHelper]
 * @param configure Optional callback, called when the database connection is being configured, to enable features such as
 * write-ahead logging or foreign key support. It should not modify the database except to configure it.
 * @param useNoBackupDirectory Sets whether to use a no backup directory or not.
 * @param windowSizeBytes Size of cursor window in bytes, per [android.database.CursorWindow] (Android 28+ only), or null to use the default.
 */
fun SqlNormalizedCacheFactory(
    context: Context,
    name: String? = "apollo.db",
    factory: SupportSQLiteOpenHelper.Factory = FrameworkSQLiteOpenHelperFactory(),
    configure: ((SupportSQLiteDatabase) -> Unit)? = null,
    useNoBackupDirectory: Boolean = false,
    windowSizeBytes: Long? = null,
): NormalizedCacheFactory {
  val synchronousSchema = SqlRecordDatabase.Schema.synchronous()
  val filePath = when {
    name == null -> {
      null
    }

    name.startsWith("/") -> {
      // Absolute path: keep as-is
      name
    }

    else -> {
      // Old versions of the library used to store the database in the database directory.
      // If such file exists, use it, otherwise, use the cache directory.
      (context.getDatabasePath(name).takeIf { it.exists() } ?: context.cacheDir.resolve(name)).absolutePath
    }
  }
  return SqlNormalizedCacheFactory(
      AndroidSqliteDriver(
          schema = synchronousSchema,
          context = context.applicationContext,
          name = filePath,
          factory = factory,
          callback = object : AndroidSqliteDriver.Callback(synchronousSchema) {
            override fun onConfigure(db: SupportSQLiteDatabase) {
              super.onConfigure(db)
              configure?.invoke(db)
            }
          },
          useNoBackupDirectory = useNoBackupDirectory,
          windowSizeBytes = windowSizeBytes,
      ),
  )
}

private fun createDriver(name: String?): SqlDriver {
  return AndroidSqliteDriver(
      schema = SqlRecordDatabase.Schema.synchronous(),
      context = ApolloInitializer.context,
      name = name,
      factory = FrameworkSQLiteOpenHelperFactory(),
  )
}

