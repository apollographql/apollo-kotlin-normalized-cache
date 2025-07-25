@file:Suppress("PackageDirectoryMismatch")

package com.apollographql.cache.normalized

import com.apollographql.apollo.api.ApolloRequest
import com.apollographql.apollo.api.ExecutionContext
import com.apollographql.apollo.api.MutableExecutionOptions
import com.apollographql.apollo.api.Operation

internal class ErrorsReplaceCachedValuesContext(val value: Boolean) : ExecutionContext.Element {
  override val key: ExecutionContext.Key<*>
    get() = Key

  companion object Key : ExecutionContext.Key<ErrorsReplaceCachedValuesContext>
}

internal val <D : Operation.Data> ApolloRequest<D>.errorsReplaceCachedValues
  get() = executionContext[ErrorsReplaceCachedValuesContext]?.value ?: false

/**
 * @param errorsReplaceCachedValues Whether field errors should replace existing values in the cache (true) or be discarded (false).
 *
 * Default: false
 */
fun <T> MutableExecutionOptions<T>.errorsReplaceCachedValues(errorsReplaceCachedValues: Boolean) = addExecutionContext(
    ErrorsReplaceCachedValuesContext(errorsReplaceCachedValues)
)
