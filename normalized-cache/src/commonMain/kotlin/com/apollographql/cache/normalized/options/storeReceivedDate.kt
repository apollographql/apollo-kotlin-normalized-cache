@file:Suppress("PackageDirectoryMismatch")

package com.apollographql.cache.normalized

import com.apollographql.apollo.api.ExecutionContext
import com.apollographql.apollo.api.ExecutionOptions
import com.apollographql.apollo.api.MutableExecutionOptions
import com.apollographql.cache.normalized.api.ApolloCacheHeaders
import com.apollographql.cache.normalized.api.CacheHeaders

internal class StoreReceivedDateContext(val value: Boolean) : ExecutionContext.Element {
  override val key: ExecutionContext.Key<*>
    get() = Key

  companion object Key : ExecutionContext.Key<StoreReceivedDateContext>
}

internal val ExecutionOptions.storeReceivedDate
  get() = executionContext[StoreReceivedDateContext]?.value ?: false


/**
 * @param storeReceivedDate Whether to store the receive date in the cache.
 *
 * Default: false
 */
fun <T> MutableExecutionOptions<T>.storeReceivedDate(storeReceivedDate: Boolean) = addExecutionContext(
    StoreReceivedDateContext(storeReceivedDate)
)

internal fun nowReceivedDateCacheHeaders(clock: () -> Long): CacheHeaders {
  return CacheHeaders.Builder().addHeader(ApolloCacheHeaders.RECEIVED_DATE, (clock() / 1000).toString()).build()
}
