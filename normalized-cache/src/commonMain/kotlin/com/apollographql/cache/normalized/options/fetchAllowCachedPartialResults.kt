package com.apollographql.cache.normalized.options

import com.apollographql.apollo.api.ExecutionContext
import com.apollographql.apollo.api.ExecutionOptions
import com.apollographql.apollo.api.MutableExecutionOptions

internal class FetchAllowCachedPartialResultsContext(val value: Boolean) : ExecutionContext.Element {
  override val key: ExecutionContext.Key<*>
    get() = Key

  companion object Key : ExecutionContext.Key<FetchAllowCachedPartialResultsContext>
}

internal val ExecutionOptions.allowCachedPartialResults: Boolean
  get() = executionContext[FetchAllowCachedPartialResultsContext]?.value ?: false

/**
 * Sets whether to allow partial results to be returned from the cache.
 * If set to false, if any field is missing in the cache, the returned response will have a null data and a non-null exception of type [CacheMissException].
 *
 * Default: false
 */
fun <T> MutableExecutionOptions<T>.allowCachedPartialResults(allowCachedPartialResults: Boolean): T =
  addExecutionContext(FetchAllowCachedPartialResultsContext(allowCachedPartialResults))

