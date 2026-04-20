@file:Suppress("PackageDirectoryMismatch")

package com.apollographql.cache.normalized

import com.apollographql.apollo.api.MutableExecutionOptions
import com.apollographql.cache.normalized.api.ApolloCacheHeaders

/**
 * @param errorsReplaceCachedValues Whether field errors should replace existing values in the cache (true) or be discarded (false).
 *
 * Default: false
 */
fun <T> MutableExecutionOptions<T>.errorsReplaceCachedValues(errorsReplaceCachedValues: Boolean) =
  addCacheHeader(ApolloCacheHeaders.ERRORS_REPLACE_CACHED_VALUES, errorsReplaceCachedValues.toString())
