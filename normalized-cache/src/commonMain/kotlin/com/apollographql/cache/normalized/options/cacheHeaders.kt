@file:Suppress("PackageDirectoryMismatch")

package com.apollographql.cache.normalized

import com.apollographql.apollo.api.ApolloResponse
import com.apollographql.apollo.api.ExecutionContext
import com.apollographql.apollo.api.ExecutionOptions
import com.apollographql.apollo.api.MutableExecutionOptions
import com.apollographql.apollo.api.Operation
import com.apollographql.cache.normalized.api.ApolloCacheHeaders
import com.apollographql.cache.normalized.api.CacheHeaders
import kotlin.time.Duration

/**
 * [CacheHeaders] carried by an [ExecutionContext].
 *
 * Several of these can coexist in one context — the client sets some, the call adds more — and they
 * are merged when read, later ones winning. This is why every instance carries its own [key]:
 * [ExecutionContext.plus] drops elements whose key is already present, so a shared key would let the
 * headers of a call silently discard those of its client.
 *
 * Merging in [fold] instead does not work: [ExecutionContext.plus] only reaches an element's `fold`
 * when it happens to sit at the far left of the combined context, so whether the headers merged
 * depended on the order the options were set in.
 */
internal class CacheHeadersContext(val value: CacheHeaders) : ExecutionContext.Element {
  override val key: ExecutionContext.Key<*> = Key()

  private class Key : ExecutionContext.Key<CacheHeadersContext>
}

private fun ExecutionContext.foldCacheHeaders(): CacheHeaders {
  var merged: CacheHeaders? = null
  fold(Unit) { _, element ->
    if (element is CacheHeadersContext) {
      merged = merged?.plus(element.value) ?: element.value
    }
  }
  return merged ?: CacheHeaders.NONE
}

internal val ExecutionOptions.cacheHeaders: CacheHeaders
  get() = executionContext.foldCacheHeaders()

fun <D : Operation.Data> ApolloResponse.Builder<D>.cacheHeaders(cacheHeaders: CacheHeaders) =
  addExecutionContext(CacheHeadersContext(cacheHeaders))

val <D : Operation.Data> ApolloResponse<D>.cacheHeaders
  get() = executionContext.foldCacheHeaders()


/**
 * @param cacheHeaders additional cache headers to be passed to your [com.apollographql.cache.normalized.api.NormalizedCache]
 */
fun <T> MutableExecutionOptions<T>.cacheHeaders(cacheHeaders: CacheHeaders) = addExecutionContext(
    CacheHeadersContext(cacheHeaders)
)

/**
 * Add a cache header to be passed to your [com.apollographql.cache.normalized.api.NormalizedCache]
 */
fun <T> MutableExecutionOptions<T>.addCacheHeader(key: String, value: String) = cacheHeaders(
    CacheHeaders.Builder().addHeader(key, value).build()
)

/**
 * @param maxStale how long to accept stale fields
 */
fun <T> MutableExecutionOptions<T>.maxStale(maxStale: Duration) = addCacheHeader(
    ApolloCacheHeaders.MAX_STALE, maxStale.inWholeSeconds.toString()
)
