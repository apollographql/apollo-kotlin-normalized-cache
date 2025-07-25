package com.apollographql.cache.normalized.options

import com.apollographql.apollo.api.ExecutionContext
import com.apollographql.apollo.api.ExecutionOptions

internal class RefetchAllowCachedPartialResultsContext(val value: Boolean) : ExecutionContext.Element {
  override val key: ExecutionContext.Key<*>
    get() = Key

  companion object Key : ExecutionContext.Key<RefetchAllowCachedPartialResultsContext>
}

internal val ExecutionOptions.refetchAllowCachedPartialResults: Boolean
  get() = executionContext[RefetchAllowCachedPartialResultsContext]?.value ?: false
