package com.apollographql.cache.normalized.sql.bundled

internal actual fun getFileName(name: String?): String? {
  return when {
    name == null -> {
      null
    }

    name.startsWith("/") -> {
      name
    }

    else -> {
      // Old versions of the library used to store the database in the database directory.
      // If such file exists, use it, otherwise, use the cache directory.
      val context = ApolloInitializer.context
      (context.getDatabasePath(name).takeIf { it.exists() } ?: context.cacheDir.resolve(name)).absolutePath
    }
  }
}
