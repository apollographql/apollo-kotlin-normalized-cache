package com.apollographql.cache.normalized.options

import com.apollographql.apollo.annotations.ApolloExperimental
import com.apollographql.apollo.api.MutableExecutionOptions
import com.apollographql.cache.normalized.addCacheHeader
import com.apollographql.cache.normalized.api.ApolloCacheHeaders

/**
 * Defines how cached errors are surfaced.
 */
@ApolloExperimental
enum class CacheOnError {
  /**
   * Errors are surfaced as `null`.
   */
  NULL,

  /**
   * Errors are surfaced as `null` if the position is nullable, otherwise they are propagated to the parent position.
   */
  PROPAGATE,

  /**
   * If there are any errors, the whole response is returned as `null`.
   */
  HALT,
}

/**
 * Controls how cached errors are surfaced.
 *
 * Default: [CacheOnError.PROPAGATE]
 */
@ApolloExperimental
fun <T> MutableExecutionOptions<T>.cacheOnError(cacheOnError: CacheOnError) = addCacheHeader(ApolloCacheHeaders.ON_ERROR, cacheOnError.name)
