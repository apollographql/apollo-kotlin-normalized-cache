@file:Suppress("PackageDirectoryMismatch")

package com.apollographql.cache.normalized

import com.apollographql.apollo.api.ApolloRequest
import com.apollographql.apollo.api.ExecutionContext
import com.apollographql.apollo.api.MutableExecutionOptions
import com.apollographql.apollo.api.Operation
import com.apollographql.apollo.interceptor.ApolloInterceptor
import com.apollographql.cache.normalized.options.noCache
import com.apollographql.cache.normalized.options.onlyIfCached

internal class FetchPolicyContext(val interceptor: ApolloInterceptor) : ExecutionContext.Element {
  override val key: ExecutionContext.Key<*>
    get() = Key

  companion object Key : ExecutionContext.Key<FetchPolicyContext>
}

internal val <D : Operation.Data> ApolloRequest<D>.fetchPolicyInterceptor
  get() = executionContext[FetchPolicyContext]?.interceptor ?: DefaultFetchPolicyInterceptor

/**
 * Sets the initial [FetchPolicy]
 * This only has effects for queries. Mutations and subscriptions always use [FetchPolicy.NetworkOnly]
 */
fun <T> MutableExecutionOptions<T>.fetchPolicyInterceptor(interceptor: ApolloInterceptor) = addExecutionContext(
    FetchPolicyContext(interceptor),
)

/**
 * Sets the initial [FetchPolicy]
 * This only has effects for queries. Mutations and subscriptions always use the network only.
 */
@Suppress("UNCHECKED_CAST")
fun <T> MutableExecutionOptions<T>.fetchPolicy(fetchPolicy: FetchPolicy): T {
  // Reset first
  onlyIfCached(false)
  noCache(false)
  return when (fetchPolicy) {
    FetchPolicy.NetworkFirst -> {
      fetchPolicyInterceptor(NetworkFirstInterceptor)
    }

    FetchPolicy.CacheOnly -> onlyIfCached(true)
    FetchPolicy.NetworkOnly -> noCache(true)
    FetchPolicy.CacheFirst -> this as T
    FetchPolicy.CacheAndNetwork,
      -> {
      // CacheAndNetwork is deprecated but should still work
      fetchPolicyInterceptor(CacheAndNetworkInterceptor)
    }
  }
}
