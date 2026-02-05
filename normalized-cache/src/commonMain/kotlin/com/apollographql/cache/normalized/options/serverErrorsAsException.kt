package com.apollographql.cache.normalized.options

import com.apollographql.apollo.api.ExecutionContext
import com.apollographql.apollo.api.ExecutionOptions
import com.apollographql.apollo.api.MutableExecutionOptions

internal class ServerErrorsAsExceptionContext(val value: Boolean) : ExecutionContext.Element {
  override val key: ExecutionContext.Key<*>
    get() = Key

  companion object Key : ExecutionContext.Key<ServerErrorsAsExceptionContext>
}

internal val ExecutionOptions.serverErrorsAsException: Boolean
  get() = executionContext[ServerErrorsAsExceptionContext]?.value ?: true

/**
 * Sets whether GraphQL errors in the cache should be exposed as an exception.
 *
 * When true, if any field is an Error in the cache, the returned response will have a null data and a non-null exception of type
 * [com.apollographql.apollo.exception.ApolloGraphQLException].
 *
 * Set this to false to allow partial responses from the cache, where errors are included in the response's `errors`.
 *
 * Default: true
 */
fun <T> MutableExecutionOptions<T>.serverErrorsAsException(serverErrorsAsException: Boolean): T =
  addExecutionContext(ServerErrorsAsExceptionContext(serverErrorsAsException))

@Deprecated("Renamed to serverErrorsAsException", ReplaceWith("serverErrorsAsException(serverErrorsAsCacheMisses)"))
fun <T> MutableExecutionOptions<T>.serverErrorsAsCacheMisses(serverErrorsAsCacheMisses: Boolean): T =
  serverErrorsAsException(serverErrorsAsCacheMisses)
