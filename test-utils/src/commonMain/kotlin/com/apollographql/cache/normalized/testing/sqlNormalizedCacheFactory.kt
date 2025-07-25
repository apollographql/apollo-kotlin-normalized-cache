package com.apollographql.cache.normalized.testing

import com.apollographql.cache.normalized.api.NormalizedCacheFactory
import com.apollographql.cache.normalized.sql.SqlNormalizedCacheFactory
import kotlin.random.Random
import kotlin.random.nextULong

fun SqlNormalizedCacheFactory(): NormalizedCacheFactory {
  return SqlNormalizedCacheFactory(name = "apollo-${Random.nextULong()}.db")
}
