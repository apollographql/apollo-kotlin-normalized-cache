package com.apollographql.cache.normalized.internal

import com.apollographql.apollo.ConcurrencyInfo
import com.apollographql.apollo.api.ApolloRequest
import com.apollographql.apollo.api.ApolloResponse
import com.apollographql.apollo.api.CustomScalarAdapters
import com.apollographql.apollo.api.Mutation
import com.apollographql.apollo.api.Operation
import com.apollographql.apollo.api.Query
import com.apollographql.apollo.api.Subscription
import com.apollographql.apollo.exception.ApolloException
import com.apollographql.apollo.exception.DefaultApolloException
import com.apollographql.apollo.exception.apolloExceptionHandler
import com.apollographql.apollo.interceptor.ApolloInterceptor
import com.apollographql.apollo.interceptor.ApolloInterceptorChain
import com.apollographql.apollo.mpp.currentTimeMillis
import com.apollographql.cache.normalized.CacheInfo
import com.apollographql.cache.normalized.CacheManager
import com.apollographql.cache.normalized.api.ApolloCacheHeaders
import com.apollographql.cache.normalized.api.CacheHeaders
import com.apollographql.cache.normalized.cacheHeaders
import com.apollographql.cache.normalized.cacheInfo
import com.apollographql.cache.normalized.clock
import com.apollographql.cache.normalized.doNotStore
import com.apollographql.cache.normalized.errorsReplaceCachedValues
import com.apollographql.cache.normalized.fetchFromCache
import com.apollographql.cache.normalized.memoryCacheOnly
import com.apollographql.cache.normalized.optimisticData
import com.apollographql.cache.normalized.options.cacheMissesAsException
import com.apollographql.cache.normalized.options.cacheOnError
import com.apollographql.cache.normalized.options.serverErrorsAsException
import com.apollographql.cache.normalized.storeReceivedDate
import com.apollographql.cache.normalized.writeToCacheAsynchronously
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

internal class ApolloCacheInterceptor(
    val cacheManager: CacheManager,
) : ApolloInterceptor {
  private suspend fun <D : Operation.Data> maybeAsync(request: ApolloRequest<D>, block: suspend () -> Unit) {
    if (request.writeToCacheAsynchronously) {
      val scope = request.executionContext[ConcurrencyInfo]!!.coroutineScope
      scope.launch {
        try {
          block()
        } catch (e: Throwable) {
          apolloExceptionHandler(Exception("An exception occurred while writing to the cache asynchronously", e))
        }
      }
    } else {
      block()
    }
  }

  /**
   * @param extraKeys extra keys to publish in case there is optimistic data
   */
  private suspend fun <D : Operation.Data> maybeWriteToCache(
      request: ApolloRequest<D>,
      response: ApolloResponse<D>,
      customScalarAdapters: CustomScalarAdapters,
      extraKeys: Set<String> = emptySet(),
  ) {
    if (request.doNotStore) {
      return
    }
    if (response.data == null) {
      return
    }
    maybeAsync(request) {
      val cacheKeys = if (response.data != null) {
        var cacheHeaders = request.cacheHeaders + response.cacheHeaders
        if (request.storeReceivedDate) {
          cacheHeaders += nowReceivedDateCacheHeaders(request.clock)
        }
        if (request.memoryCacheOnly) {
          cacheHeaders += CacheHeaders.Builder().addHeader(ApolloCacheHeaders.MEMORY_CACHE_ONLY, "true").build()
        }
        if (request.errorsReplaceCachedValues) {
          cacheHeaders += CacheHeaders.Builder().addHeader(ApolloCacheHeaders.ERRORS_REPLACE_CACHED_VALUES, "true").build()
        }
        cacheManager.writeOperation(request.operation, response.data!!, response.errors, customScalarAdapters, cacheHeaders)
      } else {
        emptySet()
      }
      cacheManager.publish(cacheKeys + extraKeys)
    }
  }

  override fun <D : Operation.Data> intercept(request: ApolloRequest<D>, chain: ApolloInterceptorChain): Flow<ApolloResponse<D>> {
    return when (request.operation) {
      is Subscription -> {
        // That's a lot of unchecked casts but should be always true
        @Suppress("UNCHECKED_CAST")
        interceptSubscription(request as ApolloRequest<Subscription.Data>, chain) as Flow<ApolloResponse<D>>
      }

      is Mutation -> {
        // That's a lot of unchecked casts but should be always true
        @Suppress("UNCHECKED_CAST")
        interceptMutation(request as ApolloRequest<Mutation.Data>, chain) as Flow<ApolloResponse<D>>
      }

      is Query -> {
        // That's a lot of unchecked casts but should be always true
        @Suppress("UNCHECKED_CAST")
        interceptQuery(request as ApolloRequest<Query.Data>, chain) as Flow<ApolloResponse<D>>
      }

      else -> error("Unknown operation ${request.operation}")
    }
  }

  /**
   * Subscriptions always go to the network
   */
  private fun <D : Subscription.Data> interceptSubscription(
      request: ApolloRequest<D>,
      chain: ApolloInterceptorChain,
  ): Flow<ApolloResponse<D>> {
    val customScalarAdapters = request.customScalarAdapters

    return chain.proceed(request).onEach {
      maybeWriteToCache(request, it, customScalarAdapters)
    }
  }

  val <D : Operation.Data> ApolloRequest<D>.customScalarAdapters: CustomScalarAdapters
    get() = executionContext[CustomScalarAdapters]!!

  /**
   * Mutations always go to the network and support optimistic data
   */
  private fun <D : Mutation.Data> interceptMutation(request: ApolloRequest<D>, chain: ApolloInterceptorChain): Flow<ApolloResponse<D>> {
    val customScalarAdapters = request.customScalarAdapters

    return flow {
      val optimisticData = request.optimisticData
      if (optimisticData != null) {
        @Suppress("UNCHECKED_CAST")
        cacheManager.writeOptimisticUpdates(
            operation = request.operation,
            data = optimisticData as D,
            mutationId = request.requestUuid,
            customScalarAdapters = customScalarAdapters,
        ).also { cacheManager.publish(it) }
      }

      /**
       * This doesn't use [readFromNetwork] so that we can publish all keys all at once after the keys have been rolled back
       */
      var networkException: ApolloException? = null
      val networkResponses: Flow<ApolloResponse<D>> = chain.proceed(request)
          .onEach {
            networkException = it.exception
          }

      var optimisticKeys: Set<String>? = null

      var previousResponse: ApolloResponse<D>? = null
      networkResponses.collect { response ->
        if (optimisticData != null && previousResponse != null) {
          throw DefaultApolloException("Apollo: optimistic updates can only be applied with one network response")
        }
        previousResponse = response
        if (optimisticKeys == null) optimisticKeys = if (optimisticData != null) {
          cacheManager.rollbackOptimisticUpdates(request.requestUuid)
        } else {
          emptySet()
        }

        maybeWriteToCache(request, response, customScalarAdapters, optimisticKeys!!)
        emit(response)
      }

      if (networkException != null) {
        if (optimisticKeys == null) optimisticKeys = if (optimisticData != null) {
          cacheManager.rollbackOptimisticUpdates(request.requestUuid)
        } else {
          emptySet()
        }

        cacheManager.publish(optimisticKeys)
      }
    }
  }

  private fun <D : Query.Data> interceptQuery(request: ApolloRequest<D>, chain: ApolloInterceptorChain): Flow<ApolloResponse<D>> {
    val customScalarAdapters = request.customScalarAdapters
    val fetchFromCache = request.fetchFromCache

    return flow {
      if (fetchFromCache) {
        emit(readFromCache(request, customScalarAdapters))
      } else {
        emitAll(readFromNetwork(request, chain, customScalarAdapters))
      }
    }
  }

  private suspend fun <D : Query.Data> readFromCache(
      request: ApolloRequest<D>,
      customScalarAdapters: CustomScalarAdapters,
  ): ApolloResponse<D> {
    val cacheHeaders = request.cacheHeaders
        .newBuilder().apply {
          addHeader(ApolloCacheHeaders.CURRENT_DATE, (request.clock() / 1000).toString())
          addHeader(ApolloCacheHeaders.ON_ERROR, request.cacheOnError.name)
          if (request.memoryCacheOnly) {
            addHeader(ApolloCacheHeaders.MEMORY_CACHE_ONLY, "true")
          }
          addHeader(ApolloCacheHeaders.CACHE_MISSES_AS_EXCEPTION, request.cacheMissesAsException.toString())
          addHeader(ApolloCacheHeaders.SERVER_ERRORS_AS_EXCEPTION, request.serverErrorsAsException.toString())
        }
        .build()
    val startMillis = currentTimeMillis()
    val response = cacheManager.readOperation(
        operation = request.operation,
        customScalarAdapters = customScalarAdapters,
        cacheHeaders = cacheHeaders,
    )
    return response.newBuilder()
        .requestUuid(request.requestUuid)
        .addExecutionContext(request.executionContext)
        .cacheInfo(
            response.cacheInfo!!.newBuilder()
                .cacheStartMillis(startMillis)
                .cacheEndMillis(currentTimeMillis())
                .build(),
        )
        .isLast(true)
        .build()
  }

  private fun <D : Operation.Data> readFromNetwork(
      request: ApolloRequest<D>,
      chain: ApolloInterceptorChain,
      customScalarAdapters: CustomScalarAdapters,
  ): Flow<ApolloResponse<D>> {
    val startMillis = currentTimeMillis()
    return chain.proceed(request).onEach {
      maybeWriteToCache(request, it, customScalarAdapters)
    }.map { networkResponse ->
      networkResponse.newBuilder()
          .cacheInfo(
              CacheInfo.Builder()
                  .networkStartMillis(startMillis)
                  .networkEndMillis(currentTimeMillis())
                  .networkException(networkResponse.exception)
                  .build(),
          ).build()
    }
  }

  companion object {
    private fun nowReceivedDateCacheHeaders(clock: () -> Long): CacheHeaders {
      return CacheHeaders.Builder().addHeader(ApolloCacheHeaders.RECEIVED_DATE, (clock() / 1000).toString()).build()
    }
  }
}
