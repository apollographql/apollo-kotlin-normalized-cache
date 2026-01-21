package test

import com.apollographql.cache.normalized.CacheManager
import com.apollographql.cache.normalized.api.CacheHeaders
import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.api.DefaultCacheKeyGenerator
import com.apollographql.cache.normalized.api.DefaultCacheResolver
import com.apollographql.cache.normalized.api.DefaultRecordMerger
import com.apollographql.cache.normalized.api.ReadOnlyNormalizedCache
import com.apollographql.cache.normalized.api.Record
import com.apollographql.cache.normalized.memory.MemoryCacheFactory
import com.apollographql.cache.normalized.testing.SqlNormalizedCacheFactory
import com.apollographql.cache.normalized.testing.runTest
import kotlinx.coroutines.flow.toList
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals

class ChainedTest {
  @Test
  fun sizes() = runTest {
    val cacheManager = CacheManager(
        normalizedCacheFactory = MemoryCacheFactory().chain(SqlNormalizedCacheFactory()),
        cacheKeyGenerator = DefaultCacheKeyGenerator,
        cacheResolver = DefaultCacheResolver,
        enableOptimisticUpdates = true,
    )

    cacheManager.accessCache { cache ->
      cache.merge(Record(CacheKey("a"), fields = mapOf("name" to "Alice")), CacheHeaders.NONE, DefaultRecordMerger)
    }
    val sizes = mutableListOf<Long>()
    cacheManager.accessCache { cache ->
      var c: ReadOnlyNormalizedCache? = cache
      while (c != null) {
        sizes.add(c.size())
        c = c.nextCache
      }
    }
    assertContentEquals(listOf(43, 43, 12288), sizes)
  }

  @Test
  fun allRecords() = runTest {
    val cacheManager = CacheManager(
        normalizedCacheFactory = MemoryCacheFactory().chain(SqlNormalizedCacheFactory()),
        cacheKeyGenerator = DefaultCacheKeyGenerator,
        cacheResolver = DefaultCacheResolver,
        enableOptimisticUpdates = true,
    )

    cacheManager.accessCache { cache ->
      repeat(100) {
        cache.merge(Record(CacheKey("a$it"), fields = mapOf("id" to "$it")), CacheHeaders.NONE, DefaultRecordMerger)
      }
    }
    val allRecords = mutableListOf<List<Record>>()
    cacheManager.accessCache { cache ->
      var c: ReadOnlyNormalizedCache? = cache
      while (c != null) {
        allRecords.add(c.loadAllRecords().toList())
        c = c.nextCache
      }
    }
    assertContentEquals(listOf(100, 100, 100), allRecords.map { it.size })
    for (records in allRecords) {
      repeat(100) {
        assertContains(records.map { it.key }, CacheKey("a$it"))
      }
    }
  }
}
