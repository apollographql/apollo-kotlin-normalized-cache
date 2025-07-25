@file:Suppress("PackageDirectoryMismatch")

package com.apollographql.cache.normalized

import com.apollographql.apollo.api.ExecutionContext
import com.apollographql.apollo.api.ExecutionOptions
import com.apollographql.apollo.api.MutableExecutionOptions
import com.apollographql.apollo.mpp.currentTimeMillis

internal class ClockContext(val value: () -> Long) : ExecutionContext.Element {
  override val key: ExecutionContext.Key<*>
    get() = Key

  companion object Key : ExecutionContext.Key<ClockContext>
}

internal val ExecutionOptions.clock: (() -> Long)
  get() = executionContext[ClockContext]?.value ?: { currentTimeMillis() }

/**
 * Sets the clock used to:
 * - compute the expiration date (see [storeExpirationDate])
 * - get the received date (see [com.apollographql.cache.normalized.options.storeReceivedDate])
 * - comparing these dates to the current time in [com.apollographql.cache.normalized.api.CacheControlCacheResolver]
 *
 * This is useful for testing purposes only.
 *
 * @param clock returns the current time in milliseconds since the epoch.
 */
fun <T> MutableExecutionOptions<T>.clock(clock: () -> Long): T = addExecutionContext(ClockContext(clock))

