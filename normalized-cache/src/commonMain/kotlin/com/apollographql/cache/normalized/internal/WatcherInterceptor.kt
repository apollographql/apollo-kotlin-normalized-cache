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
import com.apollographql.cache.normalized.FetchPolicy
import com.apollographql.cache.normalized.FetchPolicyContext
import com.apollographql.cache.normalized.RefetchPolicyContext
import com.apollographql.cache.normalized.addCacheHeader
import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.api.dependentKeys
import com.apollographql.cache.normalized.api.withErrors
import com.apollographql.cache.normalized.doNotStore
import com.apollographql.cache.normalized.fetchPolicy
import com.apollographql.cache.normalized.options.noCache
import com.apollographql.cache.normalized.options.onlyIfCached
import com.apollographql.cache.normalized.options.refetchNoCache
import com.apollographql.cache.normalized.options.refetchOnlyIfCached
import com.apollographql.cache.normalized.watchContext
import com.apollographql.cache.normalized.writeToCacheAsynchronously
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch

internal val WatcherSentinel = DefaultApolloException("The watcher has started")

private class InitialResponse<D : Operation.Data>(val response: ApolloResponse<D>)

private object InitialFetchDone

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

    /**
     * The request used to read back a network response of the initial fetch once it has been
     * written, when the cache changed during the fetch: the raw response doesn't have these changes,
     * for example optimistic data, while the cache has them on top of the response.
     */
    val cacheReadRequest = watchedRequest.newBuilder()
        .fetchPolicy(FetchPolicy.CacheOnly)
        .build()

    fun record(response: ApolloResponse<D>) {
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

    fun proceedRecordingData(request: ApolloRequest<D>): Flow<ApolloResponse<D>> {
      return chain.proceed(request).onEach { record(it) }
    }

    fun isWatched(changedKeys: Set<*>): Boolean {
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
      val events = Channel<Any>(Channel.UNLIMITED)
      val subscribed = CompletableDeferred<Unit>()
      launch(start = CoroutineStart.UNDISPATCHED) {
        cacheManager.changedKeys
            .onSubscription { subscribed.complete(Unit) }
            .collect { events.send(it) }
      }
      subscribed.await()

      /**
       * The initial fetch runs alongside the handling of cache changes, so that a change made while
       * it is in flight is emitted right away rather than after its network response. Both go
       * through [events], so that they are handled one at a time, in the order they happened.
       */
      if (watchContext.fetchInitialResponses) {
        launch {
          chain.proceed(initialRequest).collect { events.send(InitialResponse(it)) }
          events.send(InitialFetchDone)
        }
      } else {
        send(ApolloResponse.Builder(request.operation, request.requestUuid).exception(WatcherSentinel).build())
      }

      var initialFetchInFlight = watchContext.fetchInitialResponses
      // Changes made before the first emission wait for it: refetching earlier could emit data ahead
      // of an initial response that is older than it.
      var hasEmitted = !watchContext.fetchInitialResponses
      val changesBeforeFirstEmission = mutableListOf<Set<*>>()
      // Whether a refetch was emitted while the initial fetch was in flight, which a later network
      // response of that fetch must not hide.
      var refetchedDuringInitialFetch = false
      // Whether an asynchronous write of the initial fetch is to be read back when it lands.
      var readBackOnInitialWrite = false

      suspend fun handleChanges(changes: List<Set<*>>) {
        var watched = false
        var readBack = false
        // Every change is checked, so that the watcher's own ones are all consumed.
        for (changedKeys in changes) {
          if (ownPublishedKeys?.consume(changedKeys) == true) {
            // A write of the initial fetch: its data has already been emitted, unless it is an
            // asynchronous write that landed after the cache was read back in its place.
            if (readBackOnInitialWrite) {
              readBackOnInitialWrite = false
              readBack = true
            }
          } else if (isWatched(changedKeys)) {
            watched = true
          }
        }
        if (watched) {
          if (initialFetchInFlight) {
            refetchedDuringInitialFetch = true
          }
          proceedRecordingData(refetchRequest).collect { send(it) }
        } else if (readBack) {
          proceedRecordingData(cacheReadRequest).collect { send(it) }
        }
      }

      suspend fun handleInitialResponse(response: ApolloResponse<D>) {
        val fromNetwork = response.executionContext[DependentKeysContext] == null
        val written = fromNetwork && response.data != null && !request.doNotStore
        if (!written || !refetchedDuringInitialFetch) {
          record(response)
          send(response)
          return
        }
        // Read the cache in its place, which has the changes made during the fetch on top of it.
        proceedRecordingData(cacheReadRequest).collect { send(it) }
        if (request.writeToCacheAsynchronously) {
          // The write may not have landed yet. If it changes anything when it does, its
          // notification triggers another read. If it changes nothing, it publishes nothing, but
          // then the read above is already up to date.
          readBackOnInitialWrite = true
        }
      }

      var lookahead: Any? = null
      while (true) {
        val event = lookahead ?: events.receive()
        lookahead = null
        when (event) {
          is InitialResponse<*> -> {
            @Suppress("UNCHECKED_CAST")
            handleInitialResponse(event.response as ApolloResponse<D>)
          }

          InitialFetchDone -> {
            initialFetchInFlight = false
            refetchedDuringInitialFetch = false
          }

          else -> {
            // Changes that piled up while the watcher was busy are handled with a single refetch.
            val changes = mutableListOf(event as Set<*>)
            while (true) {
              val next = events.tryReceive().getOrNull() ?: break
              if (next !is Set<*>) {
                lookahead = next
                break
              }
              changes += next
            }
            if (hasEmitted) {
              handleChanges(changes)
            } else {
              changesBeforeFirstEmission += changes
            }
            continue
          }
        }
        if (!hasEmitted) {
          hasEmitted = true
          handleChanges(changesBeforeFirstEmission.toList())
          changesBeforeFirstEmission.clear()
        }
      }
    }
  }
}
