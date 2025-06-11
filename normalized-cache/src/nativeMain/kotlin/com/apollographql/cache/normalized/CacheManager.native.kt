package com.apollographql.cache.normalized

internal actual fun com.apollographql.cache.normalized.CacheManager.cacheDumpProvider(): () -> Map<String, Map<String, Pair<Int, Map<String, Any?>>>> {
  return { throw UnsupportedOperationException() }
}
