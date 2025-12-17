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
enum class OnError {
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

internal class OnErrorContext(val onError: OnError) : ExecutionContext.Element {
  override val key: ExecutionContext.Key<*>
    get() = Key

  companion object Key : ExecutionContext.Key<OnErrorContext>
}

internal val <D : Operation.Data> ApolloRequest<D>.onError
  get() = executionContext[OnErrorContext]?.onError ?: OnError.PROPAGATE

/**
 * Controls how cached errors are surfaced.
 *
 * Default: [OnError.PROPAGATE]
 */
@ApolloExperimental
fun <T> MutableExecutionOptions<T>.onError(onError: OnError) = addExecutionContext(
    OnErrorContext(onError),
)
