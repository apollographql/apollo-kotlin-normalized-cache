@file:Suppress("PackageDirectoryMismatch")

package com.apollographql.cache.normalized

import com.apollographql.apollo.ApolloCall
import com.apollographql.apollo.api.ApolloRequest
import com.apollographql.apollo.api.ApolloResponse
import com.apollographql.apollo.api.ExecutionContext
import com.apollographql.apollo.api.Operation
import com.apollographql.apollo.api.Query
import com.apollographql.cache.normalized.internal.WatcherSentinel
import com.apollographql.cache.normalized.options.noCache
import com.apollographql.cache.normalized.options.onlyIfCached
import com.apollographql.cache.normalized.options.refetchNoCache
import com.apollographql.cache.normalized.options.refetchOnlyIfCached
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow

internal class WatchContext(
    /**
     * The data to derive the initially watched keys from, for the overload that does not fetch.
     */
    val data: Query.Data?,

    /**
     * Whether to execute the request once, with the fetch policy, before observing the cache.
     *
     * Doing this from the interceptor rather than as a separate execution means the operation goes
     * through the interceptor chain once instead of twice.
     */
    val fetchInitialResponses: Boolean,
) : ExecutionContext.Element {
  override val key: ExecutionContext.Key<*>
    get() = Key

  companion object Key : ExecutionContext.Key<WatchContext>
}

internal val <D : Operation.Data> ApolloRequest<D>.watchContext: WatchContext?
  get() = executionContext[WatchContext]


/**
 * Gets initial response(s) then observes the cache for any changes.
 *
 * The cache subscription is established before the last initial response is collected, so any external cache update made
 * after collecting it will be received.
 *
 * Note: when using [writeToCacheAsynchronously], the cache updates are postponed and behave as external cache updates. They may trigger emission. 
 *
 * [fetchPolicy] controls how the result is first queried (default: [FetchPolicy.CacheFirst]), while [refetchPolicy] will control the subsequent fetches (default: [FetchPolicy.CacheOnly]).
 *
 * Note: when manually updating the cache through [ApolloStore], [ApolloStore.publish] must be called for watchers to be notified.
 *
 * @see fetchPolicy
 * @see refetchPolicy
 */
fun <D : Query.Data> ApolloCall<D>.watch(): Flow<ApolloResponse<D>> {
  /**
   * The initial responses are fetched by the interceptor, which subscribes to the cache right after
   * and only then releases the last of them.
   *
   * Executing them here instead would mean a second trip through the interceptor chain to subscribe,
   * and that last response would be withheld until the trip finished - delaying it by the teardown
   * of the first flow, a dispatch, and a re-run of every interceptor ahead of the cache. Done from
   * the interceptor, the wait is a [kotlinx.coroutines.flow.SharedFlow] subscription and nothing
   * else, while the synchronisation point callers rely on is unchanged.
   *
   * See https://github.com/apollographql/apollo-kotlin/pull/3853
   */
  return copy()
      .addExecutionContext(WatchContext(data = null, fetchInitialResponses = true))
      .toFlow()
}

/**
 * Observes the cache for the given data. Unlike [watch], no initial request is executed on the network.
 * The fetch policy set by [fetchPolicy] will be used.
 */
fun <D : Query.Data> ApolloCall<D>.watch(data: D?): Flow<ApolloResponse<D>> {
  return watchInternal(data).filter { it.exception !== WatcherSentinel }
}

/**
 * Observes the cache for the given data. Unlike [watch], no initial request is executed on the network.
 * The fetch policy set by [fetchPolicy] will be used.
 */
internal fun <D : Query.Data> ApolloCall<D>.watchInternal(data: D?): Flow<ApolloResponse<D>> {
  return copy().addExecutionContext(WatchContext(data, fetchInitialResponses = false)).toFlow()
}
