package com.apollographql.cache.normalized.options

import com.apollographql.apollo.api.ExecutionContext
import com.apollographql.apollo.api.ExecutionOptions
import com.apollographql.apollo.api.MutableExecutionOptions

internal class ThrowOnCacheMissContext(val value: Boolean) : ExecutionContext.Element {
  override val key: ExecutionContext.Key<*>
    get() = Key

  companion object Key : ExecutionContext.Key<ThrowOnCacheMissContext>
}

internal val ExecutionOptions.throwOnCacheMiss: Boolean
  get() = executionContext[ThrowOnCacheMissContext]?.value ?: true

/**
 * Sets whether missing fields from the cache should result in an exception.
 *
 * When true, if any field is missing in the cache, the returned response will have a null data and a non-null exception of type
 * [com.apollographql.apollo.exception.CacheMissException].
 *
 * Set this to false to allow partial responses from the cache, where _some_ or _all_ of the fields may be missing.
 *
 * Default: true
 */
fun <T> MutableExecutionOptions<T>.throwOnCacheMiss(throwOnCacheMiss: Boolean): T =
  addExecutionContext(ThrowOnCacheMissContext(throwOnCacheMiss))

