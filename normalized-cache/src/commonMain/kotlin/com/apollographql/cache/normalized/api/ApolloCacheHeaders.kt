package com.apollographql.cache.normalized.api

import com.apollographql.apollo.annotations.ApolloExperimental

/**
 * A collection of cache headers that Apollo's implementations of [NormalizedCache] respect.
 */
object ApolloCacheHeaders {
  /**
   * When true, records from this request should not be stored in the [NormalizedCache].
   *
   * @see com.apollographql.cache.normalized.doNotStore
   */
  const val DO_NOT_STORE = "do-not-store"

  /**
   * When true, records should be stored and read from the [com.apollographql.cache.normalized.memory.MemoryCache] only.
   *
   * @see com.apollographql.cache.normalized.memoryCacheOnly
   */
  const val MEMORY_CACHE_ONLY = "memory-cache-only"

  @Deprecated(level = DeprecationLevel.ERROR, message = "This header has no effect and will be removed in a future release. Use ApolloStore.remove() instead.")
  const val EVICT_AFTER_READ = "evict-after-read"

  /**
   * The value of this header will be stored in the [Record]'s received date.
   *
   * @see com.apollographql.cache.normalized.storeReceivedDate
   */
  const val RECEIVED_DATE = "apollo-received-date"

  /**
   * The value of this header will be stored in the [Record]'s expiration date.
   *
   * @see com.apollographql.cache.normalized.storeExpirationDate
   */
  const val EXPIRATION_DATE = "apollo-expiration-date"

  /**
   * The current date, as the number of seconds since the epoch.
   */
  const val CURRENT_DATE = "apollo-current-date"

  /**
   * How long to accept stale fields.
   *
   * @see com.apollographql.cache.normalized.maxStale
   */
  const val MAX_STALE = "apollo-max-stale"

  /**
   * True if the returned data is considered stale
   */
  const val STALE = "apollo-stale"

  /**
   * When true, field errors should replace cached values.
   *
   * @see com.apollographql.cache.normalized.errorsReplaceCachedValues
   */
  const val ERRORS_REPLACE_CACHED_VALUES = "apollo-errors-replace-cached-values"

  /**
   * When true, [NormalizedCache.merge] skips merging records with the existing ones, instead, they are inserted as-is.
   */
  const val SKIP_MERGE = "skip-merge"

  /**
   * Defines how cached errors are surfaced.
   *
   * @see com.apollographql.cache.normalized.options.CacheOnError
   */
  @ApolloExperimental
  const val ON_ERROR = "on-error"

  /**
   * When true, missing fields from the cache should result in an exception.
   *
   * @see com.apollographql.cache.normalized.options.cacheMissesAsException
   */
  const val CACHE_MISSES_AS_EXCEPTION = "cache-misses-as-exception"

  /**
   * When true, GraphQL errors in the cache should be treated as cache misses.
   *
   * @see com.apollographql.cache.normalized.options.serverErrorsAsException
   */
  const val SERVER_ERRORS_AS_EXCEPTION = "server-errors-as-exception"
}
