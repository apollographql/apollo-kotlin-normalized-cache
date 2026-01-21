package com.apollographql.cache.normalized.options

import com.apollographql.apollo.annotations.ApolloExperimental
import com.apollographql.apollo.api.ApolloRequest
import com.apollographql.apollo.api.ExecutionContext
import com.apollographql.apollo.api.MutableExecutionOptions
import com.apollographql.apollo.api.Operation

/**
 * Defines how cached errors are surfaced.
 */
@ApolloExperimental
enum class CacheOnError {
  /**
   * Errors are surfaced as `null`.
   */
  NULL,

  /**
   * Errors are surfaced as `null` if the position is nullable, otherwise they are propagated to the parent position.
   */
  PROPAGATE,

  /**
   * If there are any errors, the whole response is returned as `null`.
   */
  HALT,
}

internal class CacheOnErrorContext(val cacheOnError: CacheOnError) : ExecutionContext.Element {
  override val key: ExecutionContext.Key<*>
    get() = Key

  companion object Key : ExecutionContext.Key<CacheOnErrorContext>
}

internal val <D : Operation.Data> ApolloRequest<D>.cacheOnError
  get() = executionContext[CacheOnErrorContext]?.cacheOnError ?: CacheOnError.PROPAGATE

/**
 * Controls how cached errors are surfaced.
 *
 * Default: [CacheOnError.PROPAGATE]
 */
@ApolloExperimental
fun <T> MutableExecutionOptions<T>.cacheOnError(cacheOnError: CacheOnError) = addExecutionContext(
    CacheOnErrorContext(cacheOnError),
)
