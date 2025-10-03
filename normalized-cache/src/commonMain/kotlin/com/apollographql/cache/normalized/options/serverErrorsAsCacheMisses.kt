package com.apollographql.cache.normalized.options

import com.apollographql.apollo.api.ExecutionContext
import com.apollographql.apollo.api.ExecutionOptions
import com.apollographql.apollo.api.MutableExecutionOptions

internal class ServerErrorsAsCacheMisses(val value: Boolean) : ExecutionContext.Element {
  override val key: ExecutionContext.Key<*>
    get() = Key

  companion object Key : ExecutionContext.Key<ServerErrorsAsCacheMisses>
}

internal val ExecutionOptions.serverErrorsAsCacheMisses: Boolean
  get() = executionContext[ServerErrorsAsCacheMisses]?.value ?: true

/**
 * Sets whether GraphQL errors in the cache should be treated as cache misses.
 *
 * When true, if any field is an Error in the cache, the returned response will have a null data and a non-null exception of type
 * [com.apollographql.apollo.exception.ApolloGraphQLException].
 *
 * Default: true
 */
fun <T> MutableExecutionOptions<T>.serverErrorsAsCacheMisses(serverErrorsAsCacheMisses: Boolean): T =
  addExecutionContext(ServerErrorsAsCacheMisses(serverErrorsAsCacheMisses))

