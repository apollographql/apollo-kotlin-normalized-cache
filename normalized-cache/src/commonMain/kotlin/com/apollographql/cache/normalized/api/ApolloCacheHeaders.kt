package com.apollographql.cache.normalized.api

import com.apollographql.apollo.annotations.ApolloExperimental

/**
 * A collection of cache headers that Apollo's implementations of [NormalizedCache] respect.
 */
object ApolloCacheHeaders {
  /**
   * Records from this request should not be stored in the [NormalizedCache].
   */
  const val DO_NOT_STORE = "do-not-store"

  /**
   * Records should be stored and read from the [MemoryCache] only.
   */
  const val MEMORY_CACHE_ONLY = "memory-cache-only"

  @Deprecated(level = DeprecationLevel.ERROR, message = "This header has no effect and will be removed in a future release. Use ApolloStore.remove() instead.")
  const val EVICT_AFTER_READ = "evict-after-read"

  /**
   * The value of this header will be stored in the [Record]'s received date.
   */
  const val RECEIVED_DATE = "apollo-received-date"

  /**
   * The value of this header will be stored in the [Record]'s expiration date.
   */
  const val EXPIRATION_DATE = "apollo-expiration-date"

  /**
   * The current date, as the number of seconds since the epoch.
   */
  const val CURRENT_DATE = "apollo-current-date"

  /**
   * How long to accept stale fields
   */
  const val MAX_STALE = "apollo-max-stale"

  /**
   * True if the returned data is considered stale
   */
  const val STALE = "apollo-stale"

  /**
   * True if field errors are allowed to replace cached values.
   */
  const val ERRORS_REPLACE_CACHED_VALUES = "apollo-errors-replace-cached-values"

  /**
   * When true, [NormalizedCache.merge] skips merging records with the existing ones, instead, they are inserted as-is.
   */
  const val SKIP_MERGE = "skip-merge"

  /**
   * Defines how cached errors are surfaced.
   *
   * See [com.apollographql.cache.normalized.options.OnError].
   */
  @ApolloExperimental
  const val ON_ERROR = "on-error"

  /**
   * When true, missing fields from the cache should result in an exception.
   *
   * @see com.apollographql.cache.normalized.options.throwOnCacheMiss
   */
  const val THROW_ON_CACHE_MISS = "throw-on-cache-miss"

  /**
   * When true, GraphQL errors in the cache should be treated as cache misses.
   *
   * @see com.apollographql.cache.normalized.options.ServerErrorsAsCacheMisses
   */
  const val SERVER_ERRORS_AS_CACHE_MISSES = "server-errors-as-cache-misses"
}
