@file:Suppress("PackageDirectoryMismatch")

package com.apollographql.cache.normalized

import com.apollographql.apollo.api.ExecutionContext
import com.apollographql.apollo.api.MutableExecutionOptions

internal class StoreExpirationDateContext(val value: Boolean) : ExecutionContext.Element {
  override val key: ExecutionContext.Key<*>
    get() = Key

  companion object Key : ExecutionContext.Key<StoreExpirationDateContext>
}

/**
 * @param storeExpirationDate Whether to store the expiration date in the cache.
 *
 * The expiration date is computed from the response HTTP headers
 *
 * Default: false
 */
fun <T> MutableExecutionOptions<T>.storeExpirationDate(storeExpirationDate: Boolean): T {
  addExecutionContext(StoreExpirationDateContext(storeExpirationDate))
  @Suppress("UNCHECKED_CAST")
  return this as T
}
