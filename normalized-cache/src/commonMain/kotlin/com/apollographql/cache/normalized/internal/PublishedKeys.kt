package com.apollographql.cache.normalized.internal

import com.apollographql.apollo.api.ExecutionContext
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update

/**
 * Collects the key sets that the cache writes of a request publish, so that the caller can tell a
 * cache change it caused itself apart from anyone else's.
 *
 * Only a watcher has a use for this, and only when its writes are asynchronous: a synchronous write
 * publishes before its response is emitted, which is before the watcher subscribes to
 * [com.apollographql.cache.normalized.CacheManager.changedKeys], so there is no event to tell apart.
 *
 * Sets are matched by identity: each publication is its own event, even if two of them carry equal
 * keys.
 */
internal class PublishedKeysContext : ExecutionContext.Element {
  private val published = atomic<List<Set<String>>>(emptyList())

  /**
   * Records [keys] as about to be published. Must be called before publishing them, so that whoever
   * collects the event can already see them here.
   */
  fun record(keys: Set<String>) {
    published.update { it + listOf(keys) }
  }

  /**
   * Whether [keys] is one of the recorded sets, which is then forgotten.
   */
  fun consume(keys: Set<*>): Boolean {
    var consumed = false
    published.update { recorded ->
      val index = recorded.indexOfFirst { it === keys }
      consumed = index != -1
      if (consumed) recorded.filterIndexed { i, _ -> i != index } else recorded
    }
    return consumed
  }

  override val key: ExecutionContext.Key<*>
    get() = Key

  companion object Key : ExecutionContext.Key<PublishedKeysContext>
}
