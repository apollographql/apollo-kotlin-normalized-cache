package com.apollographql.cache.normalized.disk

import com.apollographql.cache.normalized.api.NormalizedCache
import com.apollographql.cache.normalized.api.NormalizedCacheFactory
import java.io.File

actual fun DiskNormalizedCacheFactory(name: String): NormalizedCacheFactory {
  return object : NormalizedCacheFactory() {
    override fun create(): NormalizedCache {
      val dir = if (name.startsWith("/")) {
        File(name)
      } else {
        ApolloInitializer.context.cacheDir.resolve(name)
      }
      return DiskNormalizedCache(dir.absolutePath)
    }
  }
}
