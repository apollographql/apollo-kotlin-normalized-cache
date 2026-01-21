package com.apollographql.cache.normalized.rocksdb

internal actual fun getFileName(name: String): String {
  return if (name.startsWith("/")) {
    name
  } else {
    ApolloInitializer.context.cacheDir.resolve(name).absolutePath
  }
}
