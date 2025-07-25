package com.apollographql.cache.normalized.options

import com.apollographql.apollo.api.ExecutionContext
import com.apollographql.apollo.api.ExecutionOptions
import com.apollographql.apollo.api.MutableExecutionOptions

internal class FetchAllowCachedErrorsContext(val value: Boolean) : ExecutionContext.Element {
  override val key: ExecutionContext.Key<*>
    get() = Key

  companion object Key : ExecutionContext.Key<FetchAllowCachedErrorsContext>
}

/**
 * Sets whether to allow GraphQL errors to be returned from the cache.
 * If set to false, if any field is an Error in the cache, the returned response will have a null data and a non-null exception of type [ApolloGraphQLException].
 *
 * Default: false
 */
internal val ExecutionOptions.allowCachedErrors: Boolean
  get() = executionContext[FetchAllowCachedErrorsContext]?.value ?: false

fun <T> MutableExecutionOptions<T>.allowCachedErrors(allowCachedErrors: Boolean): T =
  addExecutionContext(FetchAllowCachedErrorsContext(allowCachedErrors))

