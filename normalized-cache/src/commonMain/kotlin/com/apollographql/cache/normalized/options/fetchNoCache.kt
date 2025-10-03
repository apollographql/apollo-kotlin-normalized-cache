package com.apollographql.cache.normalized.options

import com.apollographql.apollo.api.ExecutionContext
import com.apollographql.apollo.api.ExecutionOptions
import com.apollographql.apollo.api.MutableExecutionOptions

internal class FetchNoCacheContext(val value: Boolean) : ExecutionContext.Element {
  override val key: ExecutionContext.Key<*>
    get() = Key

  companion object Key : ExecutionContext.Key<FetchNoCacheContext>
}

internal val ExecutionOptions.noCache: Boolean
  get() = executionContext[FetchNoCacheContext]?.value ?: false

/**
 * Sets whether to skip the cache.
 * If set to true, the cache will not be queried at all.
 *
 * Default: false
 */
internal fun <T> MutableExecutionOptions<T>.noCache(noCache: Boolean): T {
  // noCache and onlyIfCached are mutually exclusive
  if (noCache) {
    addExecutionContext(FetchOnlyIfCachedContext(false))
  }
  return addExecutionContext(FetchNoCacheContext(noCache))
}
