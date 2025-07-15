package com.apollographql.cache.normalized

import com.apollographql.apollo.api.Error
import com.apollographql.apollo.api.json.JsonNumber
import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.api.RecordValue
import kotlinx.coroutines.runBlocking

internal actual fun CacheManager.cacheDumpProvider(): () -> Map<String, Map<String, Pair<Int, Map<String, Any?>>>> {
  return {
    runBlocking { dump() }.map { (cacheClass, cacheRecords) ->
      cacheClass.normalizedCacheName() to cacheRecords
          .mapKeys { (key, _) -> key.keyToString() }
          .mapValues { (_, record) ->
            record.size to (
                record.fields.mapValues { (_, value) ->
                  value.toExternal()
                } + mapOf("__metadata" to record.metadata.toExternal())
                )
          }
    }.toMap()
  }
}

private fun RecordValue.toExternal(): Any? {
  return when (this) {
    null -> null
    is String -> this
    is Boolean -> this
    is Int -> this
    is Long -> this
    is Double -> this
    is JsonNumber -> this
    is CacheKey -> "ApolloCacheReference{${this.keyToString()}}"
    is Error -> "ApolloCacheError{${this.message}}"
    is List<*> -> {
      map { it.toExternal() }
    }

    is Map<*, *> -> {
      mapValues { it.value.toExternal() }
    }

    else -> error("Unsupported record value type: '$this'")
  }
}
