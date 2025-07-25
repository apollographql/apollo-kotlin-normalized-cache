package com.apollographql.cache.normalized.options

import com.apollographql.apollo.api.ExecutionContext
import com.apollographql.apollo.api.ExecutionOptions
import com.apollographql.apollo.api.MutableExecutionOptions

internal class RefetchOnlyIfCachedContext(val value: Boolean) : ExecutionContext.Element {
  override val key: ExecutionContext.Key<*>
    get() = Key

  companion object Key : ExecutionContext.Key<RefetchOnlyIfCachedContext>
}

internal val ExecutionOptions.refetchOnlyIfCached: Boolean
  get() = executionContext[RefetchOnlyIfCachedContext]?.value ?: true

/**
 * Sets whether to only return results from the cache.
 * If set to true, the network will not be queried at all.
 *
 * Default: false
 */
internal fun <T> MutableExecutionOptions<T>.refetchOnlyIfCached(onlyIfCached: Boolean): T {
  // noCache and onlyIfCached are mutually exclusive
  if (onlyIfCached) {
    addExecutionContext(RefetchNoCacheContext(false))
  }
  return addExecutionContext(RefetchOnlyIfCachedContext(onlyIfCached))
}
