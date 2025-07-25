package com.apollographql.cache.normalized.options

import com.apollographql.apollo.api.ExecutionContext
import com.apollographql.apollo.api.ExecutionOptions
import com.apollographql.apollo.api.MutableExecutionOptions

internal class RefetchNoCacheContext(val value: Boolean) : ExecutionContext.Element {
  override val key: ExecutionContext.Key<*>
    get() = Key

  companion object Key : ExecutionContext.Key<RefetchNoCacheContext>
}

internal val ExecutionOptions.refetchNoCache: Boolean
  get() = executionContext[RefetchNoCacheContext]?.value ?: false

internal fun <T> MutableExecutionOptions<T>.refetchNoCache(noCache: Boolean): T {
  // noCache and onlyIfCached are mutually exclusive
  if (noCache) {
    addExecutionContext(RefetchOnlyIfCachedContext(false))
  }
  return addExecutionContext(RefetchNoCacheContext(noCache))
}
