package test

import com.apollographql.apollo.ApolloClient
import com.apollographql.cache.normalized.FetchPolicy
import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.api.IdCacheKeyGenerator
import com.apollographql.cache.normalized.api.IdCacheKeyResolver
import com.apollographql.cache.normalized.fetchPolicy
import com.apollographql.cache.normalized.logCacheMisses
import com.apollographql.cache.normalized.memory.MemoryCacheFactory
import com.apollographql.cache.normalized.normalizedCache
import com.apollographql.cache.normalized.testing.keyToString
import com.apollographql.cache.normalized.testing.runTest
import com.apollographql.mockserver.MockServer
import com.apollographql.mockserver.enqueueString
import normalizer.HeroAppearsInQuery
import normalizer.HeroNameQuery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * We're only doing this on the JVM because it saves time and the CacheMissLoggingInterceptor
 * touches mutable data from different threads
 */
class CacheMissLoggingInterceptorTest {

  @Test
  fun cacheMissLogging() = runTest {
    val recordedLogs = mutableListOf<String>()
    val mockServer = MockServer()
    val apolloClient = ApolloClient.Builder()
        .serverUrl(mockServer.url())
        .logCacheMisses {
          synchronized(recordedLogs) {
            recordedLogs.add(it)
          }
        }
        .normalizedCache(MemoryCacheFactory(), cacheKeyGenerator = IdCacheKeyGenerator(), cacheResolver = IdCacheKeyResolver())
        .build()

    mockServer.enqueueString("""
      {
        "data": {
          "hero": {
            "__typename": "Human",
            "name": "Luke"
          }
        }
      }
    """.trimIndent()
    )
    apolloClient.query(HeroNameQuery()).execute()
    assertNotNull(
        apolloClient.query(HeroAppearsInQuery()).fetchPolicy(FetchPolicy.CacheOnly).execute().exception
    )

    assertEquals(
        listOf(
            "Object '${CacheKey("QUERY_ROOT").keyToString()}' has no field named 'hero'",
            "Object '${CacheKey("hero").keyToString()}' has no field named 'appearsIn'"
        ),
        recordedLogs
    )
    mockServer.close()
    apolloClient.close()
  }
}
