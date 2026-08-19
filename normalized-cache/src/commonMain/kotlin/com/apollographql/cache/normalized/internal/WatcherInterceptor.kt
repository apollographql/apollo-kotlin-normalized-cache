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
import com.apollographql.cache.normalized.writeToCacheAsynchronously
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.transform

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
     * up front. This matters because of when the work would otherwise happen: the last of the
     * initial responses is withheld below until this interceptor has subscribed to
     * [CacheManager.changedKeys], so anything done before subscribing delays that response reaching
     * the caller.
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
     * The keys published by the cache writes of the initial fetch, when those writes are
     * asynchronous.
     *
     * Such a write lands after its response has been emitted and after the subscription below is
     * established, so the watcher is notified of a change it has itself just made and would refetch
     * to emit data it has already emitted. Its own notifications are recognized here and dropped.
     *
     * A synchronous write publishes before its response is emitted, which is before the subscription
     * exists, so there is nothing to recognize and nothing to record.
     */
    val ownPublishedKeys = if (watchContext.fetchInitialResponses && request.writeToCacheAsynchronously) {
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

    return flow {
      /**
       * The last of the initial responses, withheld until the cache subscription is established so
       * that callers can use it as a synchronization point: modifying the store once it arrives is
       * guaranteed to be observed. Subscribing first instead would make the watcher fire on the
       * initial fetch's own write.
       *
       * See https://github.com/apollographql/apollo-kotlin/pull/3853
       */
      var lastResponse: ApolloResponse<D>? = null

      if (watchContext.fetchInitialResponses) {
        proceedRecordingData(initialRequest).collect { response ->
          if (response.isLast) {
            /**
             * If we ever come here it means some interceptors built a new Flow and forgot to reset the isLast flag
             * Better safe than sorry: emit them when we realize that. This will introduce a delay in the response.
             */
            lastResponse?.let { emit(it) }
            lastResponse = response
          } else {
            emit(response)
          }
        }
      }

      emitAll(
          (cacheManager.changedKeys as SharedFlow<Any>)
              .onSubscription {
                emit(Unit)
              }
              .transform { event ->
                if (event !is Set<*>) {
                  // The marker emitted by `onSubscription`: subscribed, so the withheld response can
                  // be released. Reaching this point costs a `SharedFlow` subscription and nothing
                  // else, because the initial responses were fetched from this same execution of the
                  // interceptor chain.
                  val held = lastResponse
                  if (held != null) {
                    lastResponse = null
                    emit(held)
                  } else if (!watchContext.fetchInitialResponses) {
                    emit(ApolloResponse.Builder(request.operation, request.requestUuid).exception(WatcherSentinel).build())
                  }
                  return@transform
                }
                if (!isWatched(event)) {
                  return@transform
                }
                emitAll(proceedRecordingData(refetchRequest))
              }
      )
    }
  }
}
