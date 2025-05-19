package com.apollographql.cache.normalized.api

import com.apollographql.apollo.api.Mutation
import com.apollographql.apollo.api.Operation
import com.apollographql.apollo.api.Query
import com.apollographql.apollo.api.Subscription
import kotlin.jvm.JvmInline

/**
 * A [CacheKey] identifies an object in the cache.
 */
@JvmInline
value class CacheKey(
    /**
     * The key of the object in the cache.
     */
    val key: String,
) {
  /**
   * Builds a [CacheKey] from a typename and a list of Strings.
   *
   * This can be used for the common case where [CacheKey] use [typename] as a namespace and [values] as a path.
   */
  constructor(typename: String, values: List<String>) : this(
      buildString {
        append(typename)
        append(":")
        append(values.joinToString("+") { it.replace("\\", "\\\\").replace("+", "\\+") })
      }
  )

  /**
   * Builds a [CacheKey] from a typename and a list of Strings.
   *
   * This can be used for the common case where [CacheKey] use [typename] as a namespace and [values] as a path.
   */
  constructor(typename: String, vararg values: String) : this(typename, values.toList())

  constructor(values: List<String>) : this(
      values.joinToString("+") { it.replace("\\", "\\\\").replace("+", "\\+") }
  )

  internal fun keyToString(): String {
    return key
  }

  override fun toString() = "CacheKey(${keyToString()})"

  companion object {
    val QUERY_ROOT = CacheKey("QUERY_ROOT")
    val MUTATION_ROOT = CacheKey("MUTATION_ROOT")
    val SUBSCRIPTION_ROOT = CacheKey("SUBSCRIPTION_ROOT")
  }

  enum class Scope {
    TYPE,
    SERVICE,
  }
}

internal fun CacheKey.isRootKey(): Boolean {
  return this == CacheKey.QUERY_ROOT || this == CacheKey.MUTATION_ROOT || this == CacheKey.SUBSCRIPTION_ROOT
}

internal fun CacheKey.fieldKey(fieldName: String): String {
  return "${keyToString()}.$fieldName"
}

internal fun CacheKey.append(vararg keys: String): CacheKey {
  var cacheKey: CacheKey = this
  for (key in keys) {
    cacheKey = CacheKey("${cacheKey.key}.$key")
  }
  return cacheKey
}

internal fun Operation<*>.rootKey() = when (this) {
  is Query -> CacheKey.QUERY_ROOT
  is Mutation -> CacheKey.MUTATION_ROOT
  is Subscription -> CacheKey.SUBSCRIPTION_ROOT
  else -> throw IllegalArgumentException("Unknown operation type: ${this::class}")
}
