@file:Suppress("PackageDirectoryMismatch")

package com.apollographql.cache.normalized

import com.apollographql.apollo.api.ApolloRequest
import com.apollographql.apollo.api.ExecutionContext
import com.apollographql.apollo.api.MutableExecutionOptions
import com.apollographql.apollo.api.Operation

internal class MemoryCacheOnlyContext(val value: Boolean) : ExecutionContext.Element {
  override val key: ExecutionContext.Key<*>
    get() = Key

  companion object Key : ExecutionContext.Key<MemoryCacheOnlyContext>
}

internal val <D : Operation.Data> ApolloRequest<D>.memoryCacheOnly
  get() = executionContext[MemoryCacheOnlyContext]?.value ?: false

/**
 * @param memoryCacheOnly Whether to store and read from a memory cache only.
 *
 * Default: false
 */
fun <T> MutableExecutionOptions<T>.memoryCacheOnly(memoryCacheOnly: Boolean) = addExecutionContext(
    MemoryCacheOnlyContext(memoryCacheOnly)
)
