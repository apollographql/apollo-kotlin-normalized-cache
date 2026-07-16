@file:Suppress("PackageDirectoryMismatch")

package com.apollographql.cache.normalized

import com.apollographql.apollo.api.ExecutionContext
import com.apollographql.apollo.api.MutableExecutionOptions
import com.apollographql.apollo.interceptor.ApolloInterceptor
import com.apollographql.cache.normalized.options.refetchNoCache
import com.apollographql.cache.normalized.options.refetchOnlyIfCached

internal class RefetchPolicyContext(val interceptor: ApolloInterceptor) : ExecutionContext.Element {
  override val key: ExecutionContext.Key<*>
    get() = Key

  companion object Key : ExecutionContext.Key<RefetchPolicyContext>
}

internal val <T> MutableExecutionOptions<T>.refetchPolicyInterceptor
  get() = executionContext[RefetchPolicyContext]?.interceptor ?: DefaultFetchPolicyInterceptor

/**
 * Sets the fetch policy interceptor used when watching queries and a cache change has been published.
 * 
 * This overrides any fetch policy set with [refetchPolicy].
 *
 * Default: [DefaultFetchPolicyInterceptor]
 */
fun <T> MutableExecutionOptions<T>.refetchPolicyInterceptor(interceptor: ApolloInterceptor) = addExecutionContext(
    RefetchPolicyContext(interceptor),
)

/**
 * Sets the [FetchPolicy] used when watching queries and a cache change has been published.
 * 
 * This overrides any fetch policy interceptor set with [refetchPolicyInterceptor].
 * 
 * Default: [FetchPolicy.CacheOnly]
 */
@Suppress("UNCHECKED_CAST")
fun <T> MutableExecutionOptions<T>.refetchPolicy(fetchPolicy: FetchPolicy): T {
  // Reset first
  refetchPolicyInterceptor(DefaultFetchPolicyInterceptor)
  refetchOnlyIfCached(false)
  refetchNoCache(false)
  return when (fetchPolicy) {
    FetchPolicy.NetworkFirst -> refetchPolicyInterceptor(NetworkFirstInterceptor)
    FetchPolicy.CacheOnly -> refetchOnlyIfCached(true)
    FetchPolicy.NetworkOnly -> refetchNoCache(true)
    FetchPolicy.CacheFirst -> this as T
    FetchPolicy.CacheAndNetwork -> refetchPolicyInterceptor(CacheAndNetworkInterceptor)
  }
}
