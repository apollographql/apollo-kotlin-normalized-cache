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
      // TODO Implement MacOS specific logic to get the Application Support directory
      name
    }
  }
}
