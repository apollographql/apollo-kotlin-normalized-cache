package test

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.testing.QueueTestNetworkTransport
import com.apollographql.apollo.testing.enqueueTestResponse
import com.apollographql.cache.normalized.FetchPolicy
import com.apollographql.cache.normalized.api.CacheHeaders
import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.api.NormalizedCache
import com.apollographql.cache.normalized.api.NormalizedCacheFactory
import com.apollographql.cache.normalized.api.Record
import com.apollographql.cache.normalized.api.RecordMerger
import com.apollographql.cache.normalized.fetchPolicy
import com.apollographql.cache.normalized.memory.MemoryCache
import com.apollographql.cache.normalized.normalizedCache
import com.apollographql.cache.normalized.testing.Platform
import com.apollographql.cache.normalized.testing.currentThreadId
import com.apollographql.cache.normalized.testing.platform
import kotlinx.coroutines.test.runTest
import normalizer.HeroNameQuery
import kotlin.reflect.KClass
import kotlin.test.Test

class ThreadTests {
  class MyNormalizedCache(private val mainThreadId: String) : NormalizedCache {
    val delegate = MemoryCache()
    override fun merge(record: Record, cacheHeaders: CacheHeaders, recordMerger: RecordMerger): Set<String> {
      check(currentThreadId() != mainThreadId) {
        "Cache access on main thread"
      }
      return delegate.merge(record, cacheHeaders, recordMerger)
    }

    override fun merge(records: Collection<Record>, cacheHeaders: CacheHeaders, recordMerger: RecordMerger): Set<String> {
      check(currentThreadId() != mainThreadId) {
        "Cache access on main thread"
      }
      return delegate.merge(records, cacheHeaders, recordMerger)
    }

    override fun clearAll() {
      check(currentThreadId() != mainThreadId) {
        "Cache access on main thread"
      }
      return delegate.clearAll()
    }

    override fun remove(cacheKey: CacheKey, cascade: Boolean): Boolean {
      check(currentThreadId() != mainThreadId) {
        "Cache access on main thread"
      }
      return delegate.remove(cacheKey, cascade)
    }

    override fun remove(cacheKeys: Collection<CacheKey>, cascade: Boolean): Int {
      check(currentThreadId() != mainThreadId) {
        "Cache access on main thread"
      }
      return delegate.remove(cacheKeys, cascade)
    }

    override fun loadRecord(key: CacheKey, cacheHeaders: CacheHeaders): Record? {
      check(currentThreadId() != mainThreadId) {
        "Cache access on main thread"
      }
      return delegate.loadRecord(key, cacheHeaders)
    }

    override fun loadRecords(keys: Collection<CacheKey>, cacheHeaders: CacheHeaders): Collection<Record> {
      check(currentThreadId() != mainThreadId) {
        "Cache access on main thread"
      }
      return delegate.loadRecords(keys, cacheHeaders)
    }

    override fun dump(): Map<KClass<*>, Map<CacheKey, Record>> {
      check(currentThreadId() != mainThreadId) {
        "Cache access on main thread"
      }
      return delegate.dump()
    }
  }

  class MyMemoryCacheFactory(val mainThreadId: String) : NormalizedCacheFactory() {
    override fun create(): NormalizedCache {
      return MyNormalizedCache(mainThreadId)
    }

  }

  @Test
  fun cacheIsNotReadFromTheMainThread() = runTest {
    if (platform() == Platform.Js) {
      return@runTest
    }

    val apolloClient = ApolloClient.Builder()
        .normalizedCache(MyMemoryCacheFactory(currentThreadId()))
        .networkTransport(QueueTestNetworkTransport())
        .build()

    val data = HeroNameQuery.Data(HeroNameQuery.Hero("Human", "Luke"))
    val query = HeroNameQuery()
    apolloClient.enqueueTestResponse(query, data)

    apolloClient.query(query).execute()
    apolloClient.query(query).fetchPolicy(FetchPolicy.CacheOnly).execute()
    apolloClient.close()
  }
}
