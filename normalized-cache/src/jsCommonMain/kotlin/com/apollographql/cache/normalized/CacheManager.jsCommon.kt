package com.apollographql.cache.normalized

internal actual fun CacheManager.cacheDumpProvider(): () -> Map<String, Map<String, Pair<Int, Map<String, Any?>>>> {
  return { throw UnsupportedOperationException() }
}
