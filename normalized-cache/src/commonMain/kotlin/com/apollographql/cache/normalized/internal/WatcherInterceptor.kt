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
import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.api.dependentKeys
import com.apollographql.cache.normalized.api.withErrors
import com.apollographql.cache.normalized.watchContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.emitAll
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
     * Computing the keys normalizes the whole data again, which is significant work for large
     * operations. The keys are only ever read to filter an incoming cache change, so they are
     * computed on the first such change rather than up front.
     *
     * This matters because of when the work would otherwise happen:
     * [com.apollographql.cache.normalized.watch] withholds the last of its initial responses until
     * this interceptor has subscribed to [CacheManager.changedKeys], so anything done before
     * subscribing delays that response reaching the caller. The same applies to the responses of a
     * refetch below, which is why those only record the data and leave the keys stale.
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

    return (cacheManager.changedKeys as SharedFlow<Any>)
        .onSubscription {
          emit(Unit)
        }
        .transform { event ->
          if (event !is Set<*>) {
            // The marker emitted by `onSubscription`. Answered without computing the watched keys,
            // so that the initial response of `watch` is not held back by normalization.
            emit(ApolloResponse.Builder(request.operation, request.requestUuid).exception(WatcherSentinel).build())
            return@transform
          }
          if (!isWatched(event)) {
            return@transform
          }
          emitAll(
              chain.proceed(request)
                  .onEach { response ->
                    if (response.data != null) {
                      dataToWatch = response.data
                      errorsToWatch = response.errors
                      watchedKeysAreStale = true
                    }
                  }
          )
        }
  }
}
