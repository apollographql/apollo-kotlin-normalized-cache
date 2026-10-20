package com.apollographql.cache.normalized.internal

import com.apollographql.apollo.api.ApolloRequest
import com.apollographql.apollo.api.ApolloResponse
import com.apollographql.apollo.api.CustomScalarAdapters
import com.apollographql.apollo.api.Error
import com.apollographql.apollo.api.Operation
import com.apollographql.apollo.api.Query
import com.apollographql.apollo.exception.DefaultApolloException
import com.apollographql.apollo.interceptor.ApolloInterceptor
import com.apollographql.apollo.interceptor.ApolloInterceptorChain
import com.apollographql.cache.normalized.CacheManager
import com.apollographql.cache.normalized.DefaultFetchPolicyInterceptor
import com.apollographql.cache.normalized.FetchPolicyContext
import com.apollographql.cache.normalized.RefetchPolicyContext
import com.apollographql.cache.normalized.addCacheHeader
import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.api.dependentKeys
import com.apollographql.cache.normalized.api.withErrors
import com.apollographql.cache.normalized.options.noCache
import com.apollographql.cache.normalized.options.onlyIfCached
import com.apollographql.cache.normalized.options.refetchNoCache
import com.apollographql.cache.normalized.options.refetchOnlyIfCached
import com.apollographql.cache.normalized.watchContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch

internal val WatcherSentinel = DefaultApolloException("The watcher has started")

internal class WatcherInterceptor(val cacheManager: CacheManager) : ApolloInterceptor {
  override fun <D : Operation.Data> intercept(request: ApolloRequest<D>, chain: ApolloInterceptorChain): Flow<ApolloResponse<D>> {
    val watchContext = request.watchContext ?: return chain.proceed(request)

    check(request.operation is Query) {
      "It's impossible to watch a mutation or subscription"
    }

    val customScalarAdapters = request.executionContext[CustomScalarAdapters]!!

    /**
     * The data whose dependent keys are watched, and the keys themselves.
     *
     * A response served from the cache reports the keys it was read from, which are the keys to
     * watch, so there is nothing left to compute for it. A response from the network does not, and
     * neither does the data given to `watch(data)`: those are normalized again to get their keys.
     *
     * Normalizing is significant work for large operations, and the keys are only ever read to
     * filter an incoming cache change, so it is deferred to the first such change rather than done
     * up front, where it would delay the next response reaching the caller.
     */
    var dataToWatch: Operation.Data? = watchContext.data
    var errorsToWatch: List<Error>? = null
    var watchedKeys: Set<String>? = null
    var watchedKeysAreStale = true

    @Suppress("UNCHECKED_CAST")
    fun computeWatchedKeysIfStale() {
      if (!watchedKeysAreStale) {
        return
      }
      watchedKeysAreStale = false
      val data = dataToWatch
      watchedKeys = if (data == null) {
        null
      } else {
        val dataWithErrors = (data as D).withErrors(
            executable = request.operation,
            errors = errorsToWatch,
            customScalarAdapters = customScalarAdapters,
        )
        cacheManager.normalize(
            executable = request.operation,
            dataWithErrors = dataWithErrors,
            rootKey = CacheKey.QUERY_ROOT,
            customScalarAdapters = customScalarAdapters,
        ).values.dependentKeys()
      }
    }

    /**
     * The request, asking the cache reads it triggers to report the field keys they read.
     */
    val watchedRequest = request.newBuilder()
        .addCacheHeader(COLLECT_DEPENDENT_KEYS, "true")
        .build()

    /**
     * The keys published by the cache writes of the initial fetch.
     *
     * The subscription below is established before the initial fetch, so the watcher is notified of
     * the changes the fetch makes itself and would refetch to emit data it has already emitted. Its
     * own notifications are recognized here and dropped.
     */
    val ownPublishedKeys = if (watchContext.fetchInitialResponses) {
      PublishedKeysContext()
    } else {
      null
    }

    /**
     * The request used for the initial fetch.
     */
    val initialRequest = if (ownPublishedKeys == null) {
      watchedRequest
    } else {
      watchedRequest.newBuilder()
          .addExecutionContext(ownPublishedKeys)
          .build()
    }

    /**
     * The request used when the cache changes.
     *
     * `watch()` fetches its initial responses with the fetch policy and refetches with the refetch
     * policy, so the latter has to be applied here rather than to the whole call. `watch(data)` has
     * no initial fetch and keeps the fetch policy throughout.
     *
     * A refetch does not report what it publishes: it runs while subscribed either way, so being
     * notified of its own write is not specific to writing asynchronously and is left as is.
     */
    val refetchRequest = if (watchContext.fetchInitialResponses) {
      watchedRequest.newBuilder()
          .addExecutionContext(
              FetchPolicyContext(request.executionContext[RefetchPolicyContext]?.interceptor ?: DefaultFetchPolicyInterceptor),
          )
          .noCache(request.refetchNoCache)
          .onlyIfCached(request.refetchOnlyIfCached)
          .build()
    } else {
      watchedRequest
    }

    fun proceedRecordingData(request: ApolloRequest<D>): Flow<ApolloResponse<D>> {
      return chain.proceed(request)
          .onEach { response ->
            val readKeys = response.executionContext[DependentKeysContext]?.dependentKeys
            if (readKeys != null) {
              // Read from the cache, which reported the keys it read: nothing left to compute, and no
              // need to hold on to the data.
              watchedKeys = readKeys
              watchedKeysAreStale = false
              dataToWatch = null
              errorsToWatch = null
            } else if (response.data != null) {
              dataToWatch = response.data
              errorsToWatch = response.errors
              watchedKeysAreStale = true
            }
          }
    }

    fun isWatched(changedKeys: Set<*>): Boolean {
      if (ownPublishedKeys?.consume(changedKeys) == true) {
        // The initial fetch's own asynchronous write: its data has already been emitted.
        return false
      }
      if (changedKeys === CacheManager.ALL_KEYS) {
        // Matches regardless of the watched keys, so there is no need to compute them.
        return true
      }
      computeWatchedKeysIfStale()
      val watched = watchedKeys ?: return true

      @Suppress("UNCHECKED_CAST")
      return (changedKeys as Set<String>).anyIntersection(watched)
    }

    return channelFlow {
      /**
       * Subscribed before the initial fetch, so that the changes made while it is in flight are not
       * missed, for example an optimistic mutation started between the cache and network
       * responses of [com.apollographql.cache.normalized.FetchPolicy.CacheAndNetwork]. It also keeps
       * the synchronization point callers rely on: modifying the store once the last initial
       * response has arrived is guaranteed to be observed.
       *
       * See https://github.com/apollographql/apollo-kotlin/pull/3853
       *
       * Collected undispatched, so that the subscription is established before `launch` returns
       * rather than after a dispatch. Buffered without limit, so that cache writers are not
       * suspended while the watcher is busy fetching.
       */
      val changes = Channel<Set<*>>(Channel.UNLIMITED)
      val subscribed = CompletableDeferred<Unit>()
      launch(start = CoroutineStart.UNDISPATCHED) {
        cacheManager.changedKeys
            .onSubscription { subscribed.complete(Unit) }
            .collect { changes.send(it) }
      }
      subscribed.await()

      if (watchContext.fetchInitialResponses) {
        proceedRecordingData(initialRequest).collect { send(it) }
      } else {
        send(ApolloResponse.Builder(request.operation, request.requestUuid).exception(WatcherSentinel).build())
      }

      while (true) {
        // Changes that piled up, such as the ones made during the initial fetch, are handled with a
        // single refetch. Every change is checked, so that the watcher's own ones are all consumed.
        var watched = isWatched(changes.receive())
        while (true) {
          val next = changes.tryReceive().getOrNull() ?: break
          if (isWatched(next)) {
            watched = true
          }
        }
        if (watched) {
          proceedRecordingData(refetchRequest).collect { send(it) }
        }
      }
    }
  }
}
