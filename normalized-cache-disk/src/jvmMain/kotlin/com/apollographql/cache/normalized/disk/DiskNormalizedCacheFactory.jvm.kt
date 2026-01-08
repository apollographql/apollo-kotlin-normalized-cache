package com.apollographql.cache.normalized.disk

import com.apollographql.cache.normalized.api.NormalizedCache
import com.apollographql.cache.normalized.api.NormalizedCacheFactory
import java.io.File

actual fun DiskNormalizedCacheFactory(name: String): NormalizedCacheFactory {
  return object : NormalizedCacheFactory() {
    override fun create(): NormalizedCache {
      return DiskNormalizedCache(File("${System.getProperty("user.home")}/.apollo/$name").absolutePath)
    }
  }
}
