package com.apollographql.cache.normalized.testing

import com.apollographql.cache.normalized.api.NormalizedCacheFactory
import com.apollographql.cache.normalized.disk.DiskNormalizedCacheFactory
import kotlin.random.Random
import kotlin.random.nextULong

fun DiskNormalizedCacheFactory(): NormalizedCacheFactory {
  return DiskNormalizedCacheFactory(name = "apollo-${Random.nextULong()}")
}
