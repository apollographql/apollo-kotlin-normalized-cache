package com.apollographql.cache.normalized.internal

import com.apollographql.apollo.api.ExecutionContext

/**
 * Cache header asking a read to report the field keys it read, as
 * [CacheBatchReaderData.dependentKeys] and then as a [DependentKeysContext] on the response.
 *
 * Only watchers have a use for them, and collecting them costs a key per field read, so a read that
 * did not ask for them does not pay for them.
 */
internal const val COLLECT_DEPENDENT_KEYS = "collect-dependent-keys"

/**
 * The field keys a response was read from.
 *
 * Only present on responses served by a cache read made with [COLLECT_DEPENDENT_KEYS] set to true.
 */
internal class DependentKeysContext(val dependentKeys: Set<String>) : ExecutionContext.Element {
  override val key: ExecutionContext.Key<*>
    get() = Key

  companion object Key : ExecutionContext.Key<DependentKeysContext>
}
