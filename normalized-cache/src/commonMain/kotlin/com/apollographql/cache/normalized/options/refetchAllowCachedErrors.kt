package com.apollographql.cache.normalized.options

import com.apollographql.apollo.api.ExecutionContext
import com.apollographql.apollo.api.ExecutionOptions

internal class RefetchAllowCachedErrorsContext(val value: Boolean) : ExecutionContext.Element {
  override val key: ExecutionContext.Key<*>
    get() = Key

  companion object Key : ExecutionContext.Key<RefetchAllowCachedErrorsContext>
}

internal val ExecutionOptions.refetchAllowCachedErrors: Boolean
  get() = executionContext[RefetchAllowCachedErrorsContext]?.value ?: false
