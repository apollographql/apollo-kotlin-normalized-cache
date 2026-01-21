package com.apollographql.cache.normalized.rocksdb

internal actual fun getFileName(name: String): String {
  return if (name.startsWith("/")) {
    name
  } else {
    // TODO Implement Apple specific logic to get the Application Support directory
    name
  }
}
