package test

import com.apollographql.apollo.ApolloClient
import com.apollographql.cache.normalized.CacheManager
import com.apollographql.cache.normalized.api.CacheHeaders
import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.cacheManager
import com.apollographql.cache.normalized.memory.MemoryCacheFactory
import com.apollographql.cache.normalized.testing.runTest
import com.apollographql.cache.normalized.writeToCacheAsynchronously
import com.apollographql.mockserver.MockServer
import com.apollographql.mockserver.enqueueString
import fixtures.HeroAndFriendsNameResponse
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import normalizer.HeroAndFriendsNamesQuery
import normalizer.type.Episode
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * These tests are only on the JVM as on native all the cache operations are serialized so it's impossible to read the cache before it
 * has been written and confirm/infirm the test. Maybe we could do something with an AtomicReference or something like this
 */
class WriteToCacheAsynchronouslyTest {
  private lateinit var mockServer: MockServer
  private lateinit var apolloClient: ApolloClient
  private lateinit var cacheManager: CacheManager
  private var dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

  private suspend fun setUp() {
    cacheManager = CacheManager(MemoryCacheFactory())
    mockServer = MockServer()
    apolloClient = ApolloClient.Builder()
        .serverUrl(mockServer.url())
        .dispatcher(dispatcher)
        .cacheManager(cacheManager)
        .build()
  }

  private suspend fun tearDown() {
    mockServer.close()
    dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
  }

  /**
   * Write to cache asynchronously, make sure records are not in cache when we receive the response
   */
  @Test
  fun writeToCacheAsynchronously() = runTest({ setUp() }, { tearDown() }) {
    withContext(dispatcher) {
      val query = HeroAndFriendsNamesQuery(Episode.JEDI)

      mockServer.enqueueString(HeroAndFriendsNameResponse)
      apolloClient.query(query)
          .writeToCacheAsynchronously(true)
          .execute()


      val record = cacheManager.accessCache { it.loadRecord(QUERY_ROOT_KEY, CacheHeaders.NONE) }
      assertNull(record)
    }
  }

  /**
   * Write to cache synchronously, make sure records are in cache when we receive the response
   */
  @Test
  fun writeToCacheSynchronously() = runTest({ setUp() }, { tearDown() }) {
    withContext(dispatcher) {
      val query = HeroAndFriendsNamesQuery(Episode.JEDI)

      mockServer.enqueueString(HeroAndFriendsNameResponse)
      apolloClient.query(query)
          .writeToCacheAsynchronously(false)
          .execute()

      val record = cacheManager.accessCache { it.loadRecord(QUERY_ROOT_KEY, CacheHeaders.NONE) }
      assertNotNull(record)
    }
  }

  companion object {
    val QUERY_ROOT_KEY = CacheKey.QUERY_ROOT
  }
}
