package com.apollographql.cache.normalized.options

import com.apollographql.apollo.api.MutableExecutionOptions
import com.apollographql.cache.normalized.addCacheHeader
import com.apollographql.cache.normalized.api.ApolloCacheHeaders

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
  addCacheHeader(ApolloCacheHeaders.SERVER_ERRORS_AS_EXCEPTION, serverErrorsAsException.toString())

@Deprecated("Renamed to serverErrorsAsException", ReplaceWith("serverErrorsAsException(serverErrorsAsCacheMisses)"))
fun <T> MutableExecutionOptions<T>.serverErrorsAsCacheMisses(serverErrorsAsCacheMisses: Boolean): T =
  serverErrorsAsException(serverErrorsAsCacheMisses)
