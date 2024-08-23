package test

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.ApolloResponse
import com.apollographql.apollo.exception.CacheMissException
import com.apollographql.apollo.testing.internal.runTest
import com.apollographql.cache.normalized.FetchPolicy
import com.apollographql.cache.normalized.api.ExpirationCacheResolver
import com.apollographql.cache.normalized.api.MaxAgeContext
import com.apollographql.cache.normalized.api.MaxAgeProvider
import com.apollographql.cache.normalized.api.MemoryCacheFactory
import com.apollographql.cache.normalized.api.NormalizedCacheFactory
import com.apollographql.cache.normalized.apolloStore
import com.apollographql.cache.normalized.fetchPolicy
import com.apollographql.cache.normalized.maxStale
import com.apollographql.cache.normalized.normalizedCache
import com.apollographql.cache.normalized.sql.SqlNormalizedCacheFactory
import com.apollographql.cache.normalized.storeExpirationDate
import com.apollographql.mockserver.MockResponse
import com.apollographql.mockserver.MockServer
import programmatic.GetUserQuery
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ServerSideExpirationTest {
  @Test
  fun memoryCache() {
    test(MemoryCacheFactory())
  }

  @Test
  fun sqlCache() {
    test(SqlNormalizedCacheFactory())
  }

  @Test
  fun chainedCache() {
    test(MemoryCacheFactory().chain(SqlNormalizedCacheFactory()))
  }

  @Suppress("JoinDeclarationAndAssignment")
  private fun test(normalizedCacheFactory: NormalizedCacheFactory) = runTest {
    val mockServer = MockServer()
    val client = ApolloClient.Builder()
        .normalizedCache(
            normalizedCacheFactory = normalizedCacheFactory,
            cacheResolver = ExpirationCacheResolver(
                // Can be any value since we don't store the receive date
                object : MaxAgeProvider {
                  override fun getMaxAge(maxAgeContext: MaxAgeContext) = 0.seconds
                }
            )
        )
        .storeExpirationDate(true)
        .serverUrl(mockServer.url())
        .build()
    client.apolloStore.clearAll()

    val query = GetUserQuery()
    val data = """
      {
        "data": {
          "user": {
            "name": "John",
            "email": "john@doe.com",
            "admin": true
          }
        }
      }
    """.trimIndent()

    var response: ApolloResponse<GetUserQuery.Data>

    // store data with an expiration date in the future
    mockServer.enqueue(
        MockResponse.Builder()
            .addHeader("Cache-Control", "max-age=10")
            .body(data)
            .build()
    )
    client.query(query).fetchPolicy(FetchPolicy.NetworkOnly).execute()
    // read from cache -> it should succeed
    response = client.query(query).fetchPolicy(FetchPolicy.CacheOnly).execute()
    assertTrue(response.data?.user?.name == "John")

    // store expired data
    mockServer.enqueue(
        MockResponse.Builder()
            .addHeader("Cache-Control", "max-age=0")
            .body(data)
            .build()
    )
    client.query(query).fetchPolicy(FetchPolicy.NetworkOnly).execute()
    // read from cache -> it should fail
    val e = client.query(GetUserQuery()).fetchPolicy(FetchPolicy.CacheOnly).execute().exception as CacheMissException
    assertTrue(e.stale)

    // read from cache with a max stale -> no cache miss
    response = client.query(GetUserQuery())
        .fetchPolicy(FetchPolicy.CacheOnly)
        .maxStale(1.seconds)
        .execute()
    assertTrue(response.data?.user?.name == "John")
  }
}
