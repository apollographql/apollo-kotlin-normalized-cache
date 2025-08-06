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
    val data: Query.Data?,
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
 * There is a guarantee that the cache is subscribed before the initial response(s) finish emitting.
 * Any update to the cache done after the initial response(s) are received will be received.
 *
 * [fetchPolicy] controls how the result is first queried, while [refetchPolicy] will control the subsequent fetches.
 *
 * Note: when manually updating the cache through [ApolloStore], [ApolloStore.publish] must be called for watchers to be notified.
 *
 * @see fetchPolicy
 * @see refetchPolicy
 */
fun <D : Query.Data> ApolloCall<D>.watch(): Flow<ApolloResponse<D>> {
  return flow {
    var lastResponse: ApolloResponse<D>? = null
    var response: ApolloResponse<D>? = null

    toFlow()
        .collect {
          response = it

          if (it.isLast) {
            if (lastResponse != null) {
              /**
               * If we ever come here it means some interceptors built a new Flow and forgot to reset the isLast flag
               * Better safe than sorry: emit them when we realize that. This will introduce a delay in the response.
               */
              println("ApolloGraphQL: extra response received after the last one")
              emit(lastResponse!!)
            }
            /**
             * Remember the last response so that we can send it after we subscribe to the store
             *
             * This allows callers to use the last element as a synchronisation point to modify the store and still have the watcher
             * receive subsequent updates
             *
             * See https://github.com/apollographql/apollo-kotlin/pull/3853
             */
            lastResponse = it
          } else {
            emit(it)
          }
        }


    copy().fetchPolicyInterceptor(refetchPolicyInterceptor)
        .noCache(refetchNoCache)
        .onlyIfCached(refetchOnlyIfCached)
        .watchInternal(response?.data)
        .collect {
          if (it.exception === WatcherSentinel) {
            if (lastResponse != null) {
              emit(lastResponse!!)
              lastResponse = null
            }
          } else {
            emit(it)
          }
        }
  }
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
  return copy().addExecutionContext(WatchContext(data)).toFlow()
}
