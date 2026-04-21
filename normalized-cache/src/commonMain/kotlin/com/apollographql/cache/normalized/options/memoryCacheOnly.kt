@file:Suppress("PackageDirectoryMismatch")

package com.apollographql.cache.normalized

import com.apollographql.apollo.api.MutableExecutionOptions
import com.apollographql.cache.normalized.api.ApolloCacheHeaders

/**
 * @param memoryCacheOnly Whether to store and read from a memory cache only.
 *
 * Default: false
 */
fun <T> MutableExecutionOptions<T>.memoryCacheOnly(memoryCacheOnly: Boolean) =
  addCacheHeader(ApolloCacheHeaders.MEMORY_CACHE_ONLY, memoryCacheOnly.toString())

