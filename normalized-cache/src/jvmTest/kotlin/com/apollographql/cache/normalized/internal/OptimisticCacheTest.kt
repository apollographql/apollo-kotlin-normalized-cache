package com.apollographql.cache.normalized.internal

import com.apollographql.cache.normalized.api.CacheHeaders
import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.api.Record
import com.apollographql.cache.normalized.memory.MemoryCache
import com.benasher44.uuid.uuid4
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OptimisticCacheTest {

  /**
   * https://github.com/apollographql/apollo-kotlin-normalized-cache/issues/330
   *
   * Race condition: 2 threads call removeOptimisticUpdates() for a different mutationId on the
   * same journal.
   */
  @Test
  fun concurrentRemoveOptimisticUpdates() {
    repeat(1000) {
      val cache = OptimisticNormalizedCache(MemoryCache())
      val cacheKey = CacheKey("key1")
      val mutationId1 = uuid4()
      val mutationId2 = uuid4()

      // Two patches for the same key, from 2 different mutations
      cache.addOptimisticUpdate(Record(cacheKey, mapOf("field" to "value1"), mutationId1))
      cache.addOptimisticUpdate(Record(cacheKey, mapOf("field" to "value2"), mutationId2))

      val executor = Executors.newFixedThreadPool(2)
      val errors = mutableListOf<Throwable>()
      executor.submit {
        try {
          cache.removeOptimisticUpdates(mutationId1)
        } catch (e: Throwable) {
          synchronized(errors) { errors.add(e) }
        }
      }
      executor.submit {
        try {
          cache.removeOptimisticUpdates(mutationId2)
        } catch (e: Throwable) {
          synchronized(errors) { errors.add(e) }
        }
      }
      executor.shutdown()
      executor.awaitTermination(1, TimeUnit.SECONDS)
      assertTrue(errors.isEmpty())
    }
  }

  @Test
  fun correctMergeWhenRemovingLastPatch() = runBlocking {
    val cache = OptimisticNormalizedCache(MemoryCache())
    val cacheKey = CacheKey("hero")

    // Three sequential optimistic patches on the same key — each sets a different / overlapping field
    cache.addOptimisticUpdate(Record(cacheKey, mapOf("name" to "Alice"), uuid4()))
    cache.addOptimisticUpdate(Record(cacheKey, mapOf("age" to 30), uuid4()))
    val lastMutationId = uuid4()
    cache.addOptimisticUpdate(Record(cacheKey, mapOf("name" to "Charlie"), lastMutationId))

    // Remove the LAST patch (mutationId3) — the one setting name="Charlie"
    // Remaining patches: [mutationId1 {name=Alice}, mutationId2 {age=30}]
    // Expected current: {name=Alice, age=30}
    cache.removeOptimisticUpdates(lastMutationId)
    val record = cache.loadRecord(cacheKey, CacheHeaders.NONE)!!
    assertEquals("Alice", record.fields["name"], "name should come from patch 1 after patch 3 is removed")
    assertEquals(30, record.fields["age"], "age should come from patch 2 after patch 3 is removed")
  }
}

