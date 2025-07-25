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

internal class CacheHeadersContext(val value: CacheHeaders) : ExecutionContext.Element {
  override val key: ExecutionContext.Key<*>
    get() = Key

  companion object Key : ExecutionContext.Key<CacheHeadersContext>
}

internal val ExecutionOptions.cacheHeaders: CacheHeaders
  get() = executionContext[CacheHeadersContext]?.value ?: CacheHeaders.NONE

fun <D : Operation.Data> ApolloResponse.Builder<D>.cacheHeaders(cacheHeaders: CacheHeaders) =
  addExecutionContext(CacheHeadersContext(cacheHeaders))

val <D : Operation.Data> ApolloResponse<D>.cacheHeaders
  get() = executionContext[CacheHeadersContext]?.value ?: CacheHeaders.NONE


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
    cacheHeaders.newBuilder().addHeader(key, value).build()
)

/**
 * @param maxStale how long to accept stale fields
 */
fun <T> MutableExecutionOptions<T>.maxStale(maxStale: Duration) = addCacheHeader(
    ApolloCacheHeaders.MAX_STALE, maxStale.inWholeSeconds.toString()
)
