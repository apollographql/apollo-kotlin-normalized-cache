@file:Suppress("PackageDirectoryMismatch")

package com.apollographql.cache.normalized

import com.apollographql.apollo.ApolloCall
import com.apollographql.apollo.api.ApolloRequest
import com.apollographql.apollo.api.ExecutionContext
import com.apollographql.apollo.api.Mutation

internal class OptimisticUpdatesContext<D : Mutation.Data>(val value: D) : ExecutionContext.Element {
  override val key: ExecutionContext.Key<*>
    get() = Key

  companion object Key : ExecutionContext.Key<OptimisticUpdatesContext<*>>
}

internal val <D : Mutation.Data> ApolloRequest<D>.optimisticData
  get() = executionContext[OptimisticUpdatesContext]?.value

/**
 * Sets the optimistic updates to write to the cache while a query is pending.
 */
fun <D : Mutation.Data> ApolloRequest.Builder<D>.optimisticUpdates(data: D) = addExecutionContext(
    OptimisticUpdatesContext(data)
)

fun <D : Mutation.Data> ApolloCall<D>.optimisticUpdates(data: D) = addExecutionContext(
    OptimisticUpdatesContext(data)
)
