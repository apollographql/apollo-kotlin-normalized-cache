package com.apollographql.cache.normalized.internal

import com.apollographql.cache.normalized.api.CacheHeaders
import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.api.DefaultRecordMerger
import com.apollographql.cache.normalized.api.Record
import com.apollographql.cache.normalized.memory.MemoryCache
import com.benasher44.uuid.uuid4
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

class OptimisticRaceTest {
  private val key = CacheKey("Animal:1")

  private fun load(cache: OptimisticNormalizedCache) = runBlocking { cache.loadRecord(key, CacheHeaders.NONE) }

  /**
   * Out-of-order completion, no concurrency: A then B optimistic on the same field, B's network response finishes first.
   */
  @Test
  fun outOfOrderRollbackSequential() {
    val cache = OptimisticNormalizedCache(MemoryCache())
    runBlocking { cache.merge(Record(key, mapOf("name" to "base", "species" to "cat")), CacheHeaders.NONE, DefaultRecordMerger) }
    val a = uuid4()
    val b = uuid4()
    cache.addOptimisticUpdate(Record(key, mapOf("name" to "A"), a))
    cache.addOptimisticUpdate(Record(key, mapOf("name" to "B", "species" to "dog"), b))
    assertEquals("B", load(cache)!!.fields["name"])

    // B finishes first
    val changedB = cache.removeOptimisticUpdates(b)
    assertEquals(setOf("Animal:1.name", "Animal:1.species"), changedB)
    assertEquals("A", load(cache)!!.fields["name"])
    assertEquals("cat", load(cache)!!.fields["species"])

    // A finishes
    cache.removeOptimisticUpdates(a)
    assertEquals("base", load(cache)!!.fields["name"])
  }

  /**
   * Two mutations write their first optimistic patch for the same record at the same time.
   */
  @Test
  fun concurrentFirstAddKeepsBothPatches() {
    var lost = 0
    val iterations = 5_000
    val pool = Executors.newFixedThreadPool(2)
    repeat(iterations) {
      val cache = OptimisticNormalizedCache(MemoryCache())
      val barrier = CyclicBarrier(2)
      val f1 = pool.submit { barrier.await(); cache.addOptimisticUpdate(Record(key, mapOf("name" to "A"), uuid4())) }
      val f2 = pool.submit { barrier.await(); cache.addOptimisticUpdate(Record(key, mapOf("species" to "dog"), uuid4())) }
      f1.get(); f2.get()
      val fields = load(cache)!!.fields
      if (fields.size != 2) lost++
    }
    pool.shutdown()
    pool.awaitTermination(5, TimeUnit.SECONDS)
    assertEquals(0, lost, "lost a patch in $lost/$iterations iterations")
  }

  /**
   * Mutation A (the only patch on the record) rolls back while mutation B adds a patch to the same record.
   */
  @Test
  fun rollbackRacingWithAddKeepsNewPatch() {
    var lost = 0
    val iterations = 5_000
    val pool = Executors.newFixedThreadPool(2)
    repeat(iterations) {
      val cache = OptimisticNormalizedCache(MemoryCache())
      val a = uuid4()
      cache.addOptimisticUpdate(Record(key, mapOf("name" to "A"), a))
      val barrier = CyclicBarrier(2)
      val f1 = pool.submit { barrier.await(); cache.removeOptimisticUpdates(a) }
      val f2 = pool.submit { barrier.await(); cache.addOptimisticUpdate(Record(key, mapOf("species" to "dog"), uuid4())) }
      f1.get(); f2.get()
      // B is still in flight, so its patch must still be visible
      if (load(cache)?.fields?.get("species") != "dog") lost++
    }
    pool.shutdown()
    pool.awaitTermination(5, TimeUnit.SECONDS)
    assertEquals(0, lost, "lost a patch in $lost/$iterations iterations")
  }
}
