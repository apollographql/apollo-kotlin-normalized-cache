package com.apollographql.cache.normalized.internal

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update

internal fun interface PublishListener {
  /**
   * Called synchronously by [DefaultCacheManager.publish], on the thread that publishes.
   */
  fun onPublish(keys: Set<String>)
}

/**
 * The changes published while a watcher fetches its initial responses, before it subscribes to
 * [com.apollographql.cache.normalized.CacheManager.changedKeys].
 *
 * A change published after the initial fetch read the cache, but before the subscription, is in
 * neither: without this, the watcher would keep its stale data until its keys change again.
 *
 * The changes made by the initial fetch itself are left out: their data is in the initial responses.
 */
internal class MissedChanges(
    private val ownPublishedKeys: PublishedKeysContext,
    private val ownWritesAreSynchronous: Boolean,
) : PublishListener {
  private val changes = atomic<List<Set<String>>>(emptyList())

  val value: List<Set<String>>
    get() = changes.value

  override fun onPublish(keys: Set<String>) {
    val isOwn = if (ownWritesAreSynchronous) {
      // Published before the subscription is established, so it never reaches it: forget it now.
      ownPublishedKeys.consume(keys)
    } else {
      // May also reach the subscription, which then forgets it.
      ownPublishedKeys.contains(keys)
    }
    if (!isOwn) {
      changes.update { it + listOf(keys) }
    }
  }
}
