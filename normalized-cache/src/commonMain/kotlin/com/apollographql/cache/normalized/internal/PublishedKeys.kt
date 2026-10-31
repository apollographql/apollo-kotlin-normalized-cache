package com.apollographql.cache.normalized.internal

import com.apollographql.apollo.api.ExecutionContext
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update

/**
 * Collects the key sets that the cache writes of a request publish, so that the caller can tell a
 * cache change it caused itself apart from anyone else's.
 *
 * Only a watcher has a use for this: it subscribes to
 * [com.apollographql.cache.normalized.CacheManager.changedKeys] before its initial fetch, so it is
 * notified of the writes of that fetch, whether they are synchronous or asynchronous.
 *
 * Sets are matched by identity: each publication is its own event, even if two of them carry equal
 * keys.
 *
 * It also tells whether any of those writes is still to land, which an asynchronous write is for a
 * while after its response has been emitted, and [onWriteLanded] is called when one does.
 */
internal class PublishedKeysContext(
    private val onWriteLanded: () -> Unit,
) : ExecutionContext.Element {
  private val published = atomic<List<Set<String>>>(emptyList())
  private val pendingWrites = atomic(0)

  /**
   * Whether a write has started and not landed yet.
   */
  val hasPendingWrites: Boolean
    get() = pendingWrites.value > 0

  /**
   * Must be called before the response being written is emitted, so that whoever receives it can
   * already tell that its write is pending.
   */
  fun writeStarted() {
    pendingWrites.incrementAndGet()
  }

  /**
   * Must be called once the write has landed, after publishing what it changed if anything, or once
   * it has failed.
   */
  fun writeLanded() {
    pendingWrites.decrementAndGet()
    onWriteLanded()
  }

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
