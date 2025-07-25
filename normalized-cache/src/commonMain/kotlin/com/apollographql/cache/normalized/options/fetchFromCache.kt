@file:Suppress("PackageDirectoryMismatch")

package com.apollographql.cache.normalized

import com.apollographql.apollo.api.ApolloRequest
import com.apollographql.apollo.api.ExecutionContext
import com.apollographql.apollo.api.Operation

internal class FetchFromCacheContext(val value: Boolean) : ExecutionContext.Element {
  override val key: ExecutionContext.Key<*>
    get() = Key

  companion object Key : ExecutionContext.Key<FetchFromCacheContext>
}

fun <D : Operation.Data> ApolloRequest.Builder<D>.fetchFromCache(fetchFromCache: Boolean) =
  addExecutionContext(FetchFromCacheContext(fetchFromCache))

val <D : Operation.Data> ApolloRequest<D>.fetchFromCache
  get() = executionContext[FetchFromCacheContext]?.value ?: false
