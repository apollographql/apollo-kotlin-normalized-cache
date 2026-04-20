package com.apollographql.cache.normalized.options

import com.apollographql.apollo.api.MutableExecutionOptions
import com.apollographql.cache.normalized.addCacheHeader
import com.apollographql.cache.normalized.api.ApolloCacheHeaders

/**
 * Sets whether missing fields from the cache should be exposed as an exception.
 *
 * When true, if any field is missing in the cache, the returned response will have a null data and a non-null exception of type
 * [com.apollographql.apollo.exception.CacheMissException].
 *
 * Set this to false to allow partial responses from the cache, where _some_ or _all_ of the fields may be missing, and Errors are included
 * in the response's `errors` to represent cache misses.
 *
 * Default: true
 */
fun <T> MutableExecutionOptions<T>.cacheMissesAsException(cacheMissesAsException: Boolean): T =
  addCacheHeader(ApolloCacheHeaders.CACHE_MISSES_AS_EXCEPTION, cacheMissesAsException.toString())

@Deprecated("Renamed to cacheMissesAsException", ReplaceWith("cacheMissesAsException(throwOnCacheMiss)"))
fun <T> MutableExecutionOptions<T>.throwOnCacheMiss(throwOnCacheMiss: Boolean): T = cacheMissesAsException(throwOnCacheMiss)
