package com.apollographql.cache.normalized

import com.apollographql.apollo.ApolloCall
import com.apollographql.apollo.api.ApolloRequest
import com.apollographql.apollo.api.ApolloResponse
import com.apollographql.apollo.api.Operation
import com.apollographql.apollo.api.Query
import com.apollographql.apollo.conflateFetchPolicyInterceptorResponses
import com.apollographql.apollo.exception.ApolloException
import com.apollographql.apollo.exception.ApolloGraphQLException
import com.apollographql.apollo.exception.CacheMissException
import com.apollographql.apollo.exception.DefaultApolloException
import com.apollographql.apollo.interceptor.ApolloInterceptor
import com.apollographql.apollo.interceptor.ApolloInterceptorChain
import com.apollographql.cache.normalized.options.noCache
import com.apollographql.cache.normalized.options.onlyIfCached
import com.apollographql.cache.normalized.options.serverErrorsAsCacheMisses
import com.apollographql.cache.normalized.options.throwOnCacheMiss
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.single

/**
 * An interceptor that emits the response from the cache first, and if there was a cache miss, emits the response(s) from the network.
 *
 * This is the default cache policy interceptor.
 *
 * If [noCache] is set to `true`, the cache is skipped and the network response is emitted directly.
 *
 * If [onlyIfCached] is set to `true`, no network request is made.
 */
val DefaultFetchPolicyInterceptor = object : ApolloInterceptor {
  override fun <D : Operation.Data> intercept(
      request: ApolloRequest<D>,
      chain: ApolloInterceptorChain,
  ): Flow<ApolloResponse<D>> {
    return flow {
      if (!request.noCache) {
        val cacheResponse = chain.proceed(
            request = request
                .newBuilder()
                .fetchFromCache(true)
                .build(),
        ).single()
            .errorsAsException(throwOnCacheMiss = request.throwOnCacheMiss, serverErrorsAsCacheMisses = request.serverErrorsAsCacheMisses)
        emit(
            cacheResponse.newBuilder().isLast(request.onlyIfCached || cacheResponse.exception == null)
                .build(),
        )
        if (cacheResponse.exception == null) {
          return@flow
        }
      }

      if (!request.onlyIfCached) {
        val networkResponses = chain.proceed(request = request)
        emitAll(networkResponses)
      }
    }
  }
}

/**
 * An interceptor that emits the response(s) from the network first, and if there was a network error, emits the response from the cache.
 */
val NetworkFirstInterceptor = object : ApolloInterceptor {
  override fun <D : Operation.Data> intercept(request: ApolloRequest<D>, chain: ApolloInterceptorChain): Flow<ApolloResponse<D>> {
    return flow {
      var networkException: ApolloException? = null

      val networkResponses = chain.proceed(
          request = request,
      ).onEach { response ->
        if (response.exception != null && networkException == null) {
          networkException = response.exception
        }
      }.map { response ->
        if (networkException != null) {
          response.newBuilder()
              .isLast(false)
              .build()
        } else {
          response
        }
      }

      emitAll(networkResponses)
      if (networkException == null) {
        return@flow
      }

      val cacheResponse = chain.proceed(
          request = request
              .newBuilder()
              .fetchFromCache(true)
              .build(),
      ).single()
          .errorsAsException(throwOnCacheMiss = request.throwOnCacheMiss, serverErrorsAsCacheMisses = request.serverErrorsAsCacheMisses)
      emit(cacheResponse)
    }
  }
}

/**
 * An interceptor that emits the response from the cache first, and then emits the response(s) from the network.
 *
 * Warning: this can emit multiple successful responses, therefore [ApolloCall.execute] should not be used with this fetch policy.
 * Use only with [ApolloCall.toFlow] or [ApolloCall.watch].
 *
 */
val CacheAndNetworkInterceptor = object : ApolloInterceptor {
  override fun <D : Operation.Data> intercept(request: ApolloRequest<D>, chain: ApolloInterceptorChain): Flow<ApolloResponse<D>> {
    return flow {
      val cacheResponse = chain.proceed(
          request = request
              .newBuilder()
              .fetchFromCache(true)
              .build(),
      ).single()
          .errorsAsException(throwOnCacheMiss = request.throwOnCacheMiss, serverErrorsAsCacheMisses = request.serverErrorsAsCacheMisses)

      emit(cacheResponse.newBuilder().isLast(false).build())

      val networkResponses = chain.proceed(request)
      emitAll(networkResponses)
    }
  }
}

/**
 * If this response has errors, returns a response with an exception, otherwise returns this response.
 * This can be used to accommodate [com.apollographql.apollo.ApolloCall.execute] which splits responses based on exceptions and should only be called on cache responses.
 */
fun <D : Operation.Data> ApolloResponse<D>.errorsAsException(): ApolloResponse<D> {
  return if (cacheInfo?.isCacheHit == true) {
    this
  } else {
    val exception = errors.orEmpty().map { it.cacheMissException ?: ApolloGraphQLException(it) }.reduceOrNull { acc, e ->
      acc.addSuppressed(e)
      acc
    }
    newBuilder()
        .exception(exception)
        .data(null)
        .errors(null)
        .build()
  }
}

private fun <D : Operation.Data> ApolloResponse<D>.errorsAsException(
    throwOnCacheMiss: Boolean,
    serverErrorsAsCacheMisses: Boolean,
): ApolloResponse<D> {
  return if (!throwOnCacheMiss && !serverErrorsAsCacheMisses) {
    this
  } else {
    val cacheMissException = if (!throwOnCacheMiss) {
      null
    } else {
      errors.orEmpty().mapNotNull { it.cacheMissException }.reduceOrNull { acc, e ->
        acc.addSuppressed(e)
        acc
      }
    }
    val cachedErrorException = if (!serverErrorsAsCacheMisses) {
      null
    } else {
      errors.orEmpty().mapNotNull { if (it.cacheMissException != null) null else ApolloGraphQLException(it) }.reduceOrNull { acc, e ->
        acc.addSuppressed(e)
        acc
      }
    }
    when {
      cacheMissException != null -> {
        newBuilder()
            .exception(
                cacheMissException.apply {
                  if (cachedErrorException != null) {
                    addSuppressed(cachedErrorException)
                  }
                },
            )
            .data(null)
            .errors(null)
            .build()
      }

      cachedErrorException != null -> {
        newBuilder()
            .exception(cachedErrorException)
            .data(null)
            .errors(null)
            .build()
      }

      else -> {
        this
      }
    }
  }
}

internal object FetchPolicyRouterInterceptor : ApolloInterceptor {
  override fun <D : Operation.Data> intercept(request: ApolloRequest<D>, chain: ApolloInterceptorChain): Flow<ApolloResponse<D>> {
    if (request.operation !is Query) {
      // Subscriptions and Mutations do not support fetchPolicies
      return chain.proceed(request)
    }

    if (!request.conflateFetchPolicyInterceptorResponses) {
      // Fast path
      return request.fetchPolicyInterceptor.intercept(request, chain)
    }
    return flow {
      val exceptions = mutableListOf<ApolloException>()
      var hasEmitted = false

      request.fetchPolicyInterceptor.intercept(request, chain)
          .collect {
            if (!hasEmitted && it.exception != null) {
              // Remember to send the exception later
              exceptions.add(it.exception!!)
              return@collect
            }
            emit(
                it.newBuilder()
                    .cacheInfo(
                        it.cacheInfo!!.newBuilder()
                            .cacheMissException(exceptions.filterIsInstance<CacheMissException>().firstOrNull())
                            .networkException(exceptions.firstOrNull { it !is CacheMissException })
                            .build(),
                    )
                    .build(),
            )
            hasEmitted = true
          }

      @Suppress("DEPRECATION")
      if (!hasEmitted) {
        // If we haven't emitted anything, send a composite exception
        val exception = when (exceptions.size) {
          0 -> DefaultApolloException("No response emitted")
          1 -> exceptions.first()
          2 -> com.apollographql.apollo.exception.ApolloCompositeException(exceptions.first(), exceptions.get(1))
          else -> com.apollographql.apollo.exception.ApolloCompositeException(exceptions.first(), exceptions.get(1)).apply {
            exceptions.drop(2).forEach {
              addSuppressed(it)
            }
          }
        }

        emit(
            ApolloResponse.Builder(request.operation, request.requestUuid)
                .exception(exception)
                .build(),

            )
      }
    }
  }
}
