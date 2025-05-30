package com.apollographql.cache.normalized.testing

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout

suspend fun <T> Channel<T>.assertNoElement(timeoutMillis: Long = 300) {
  try {
    withTimeout(timeoutMillis) {
      receive()
    }
    error("An item was unexpectedly received")
  } catch (_: TimeoutCancellationException) {
    // nothing
  }
}
