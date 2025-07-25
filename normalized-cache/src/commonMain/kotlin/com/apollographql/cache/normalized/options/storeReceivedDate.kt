@file:Suppress("PackageDirectoryMismatch")

package com.apollographql.cache.normalized

import com.apollographql.apollo.api.ApolloRequest
import com.apollographql.apollo.api.ExecutionContext
import com.apollographql.apollo.api.MutableExecutionOptions
import com.apollographql.apollo.api.Operation

internal class StoreReceivedDateContext(val value: Boolean) : ExecutionContext.Element {
  override val key: ExecutionContext.Key<*>
    get() = Key

  companion object Key : ExecutionContext.Key<StoreReceivedDateContext>
}

internal val <D : Operation.Data> ApolloRequest<D>.storeReceivedDate
  get() = executionContext[StoreReceivedDateContext]?.value ?: false


/**
 * @param storeReceivedDate Whether to store the receive date in the cache.
 *
 * Default: false
 */
fun <T> MutableExecutionOptions<T>.storeReceivedDate(storeReceivedDate: Boolean) = addExecutionContext(
    StoreReceivedDateContext(storeReceivedDate)
)
