package com.apollographql.cache.normalized.internal

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

// Based on https://gist.github.com/elizarov/9a48b9709ffd508909d34fab6786acfe
// See also https://elizarov.medium.com/phantom-of-the-coroutine-afc63b03a131
internal suspend fun <T> Mutex.withReentrantLock(block: suspend () -> T): T {
  val key = ReentrantMutexContextKey(this)
  // call block directly when this mutex is already locked in the context
  if (coroutineContext[key] != null) return block()
  // otherwise add it to the context and lock the mutex
  return withContext(ReentrantMutexContextElement(key)) {
    withLock { block() }
  }
}

internal class ReentrantMutexContextElement(
    override val key: ReentrantMutexContextKey,
) : CoroutineContext.Element

internal data class ReentrantMutexContextKey(
    val mutex: Mutex,
) : CoroutineContext.Key<ReentrantMutexContextElement>
