package test

import app.cash.turbine.test
import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.ApolloResponse
import com.apollographql.apollo.api.Query
import com.apollographql.apollo.exception.CacheMissException
import com.apollographql.cache.normalized.FetchPolicy
import com.apollographql.cache.normalized.api.CacheControlCacheResolver
import com.apollographql.cache.normalized.api.DefaultCacheKeyGenerator
import com.apollographql.cache.normalized.api.DefaultCacheResolver
import com.apollographql.cache.normalized.api.GlobalMaxAgeProvider
import com.apollographql.cache.normalized.api.MaxAge
import com.apollographql.cache.normalized.api.NormalizedCacheFactory
import com.apollographql.cache.normalized.api.SchemaCoordinatesMaxAgeProvider
import com.apollographql.cache.normalized.apolloStore
import com.apollographql.cache.normalized.cacheHeaders
import com.apollographql.cache.normalized.cacheInfo
import com.apollographql.cache.normalized.fetchPolicy
import com.apollographql.cache.normalized.isFromCache
import com.apollographql.cache.normalized.maxStale
import com.apollographql.cache.normalized.memory.MemoryCacheFactory
import com.apollographql.cache.normalized.normalizedCache
import com.apollographql.cache.normalized.rocksdb.RocksDBNormalizedCacheFactory
import com.apollographql.cache.normalized.storeExpirationDate
import com.apollographql.cache.normalized.testing.SqlNormalizedCacheFactory
import com.apollographql.cache.normalized.testing.runTest
import com.apollographql.mockserver.MockResponse
import com.apollographql.mockserver.MockServer
import com.apollographql.mockserver.enqueueString
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import programmatic.GetUserEmailQuery
import programmatic.GetUserNameQuery
import programmatic.GetUserQuery
import kotlin.random.Random
import kotlin.random.nextULong
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

class ClientAndServerSideCacheControlTest {
  @Test
  fun cacheMissesMemory() {
    cacheMisses(MemoryCacheFactory())
  }

  @Test
  fun cacheMissesSql() {
    cacheMisses(SqlNormalizedCacheFactory())
  }

  @Test
  fun cacheMissesChainedSql() {
    cacheMisses(MemoryCacheFactory().chain(SqlNormalizedCacheFactory()))
  }

  @Test
  fun cacheMissesRocksDB() {
    cacheMisses(RocksDBNormalizedCacheFactory("apollo-${Random.nextULong()}"))
  }

  @Test
  fun cacheMissesChainedRocksDB() {
    cacheMisses(MemoryCacheFactory().chain(RocksDBNormalizedCacheFactory("apollo-${Random.nextULong()}")))
  }

  private fun cacheMisses(normalizedCacheFactory: NormalizedCacheFactory) = runTest {
    val mockServer = MockServer()
    val client = ApolloClient.Builder()
        .normalizedCache(
            normalizedCacheFactory = normalizedCacheFactory,
            cacheKeyGenerator = DefaultCacheKeyGenerator,
            cacheResolver = CacheControlCacheResolver(
                maxAgeProvider = SchemaCoordinatesMaxAgeProvider(
                    mapOf(
                        "User.email" to MaxAge.Duration(2.seconds),
                    ),
                    defaultMaxAge = 20.seconds,
                ),
                delegateResolver = DefaultCacheResolver,
            ),
        )
        .storeExpirationDate(true)
        .serverUrl(mockServer.url())
        .build()
    client.apolloStore.clearAll()

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

    // Store data with an expiration date 10s in the future, and a received date 10s in the past
    mockServer.enqueue(
        MockResponse.Builder()
            .addHeader("Cache-Control", "max-age=10")
            .body(data)
            .build(),
    )
    client.query(GetUserQuery()).fetchPolicy(FetchPolicy.NetworkOnly).cacheHeaders(receivedDate(currentTimeSeconds() - 10)).execute()

    // Read User.name from cache -> it should succeed
    val userNameResponse = client.query(GetUserNameQuery()).fetchPolicy(FetchPolicy.CacheOnly).execute()
    assertTrue(userNameResponse.data?.user?.name == "John")

    // Read User.email from cache -> it should fail
    var userEmailResponse = client.query(GetUserEmailQuery()).fetchPolicy(FetchPolicy.CacheOnly).execute()
    var e = userEmailResponse.exception as CacheMissException
    assertTrue(e.stale)

    // Store data with an expiration date of now, and a received date of now
    mockServer.enqueue(
        MockResponse.Builder()
            .addHeader("Cache-Control", "max-age=0")
            .body(data)
            .build(),
    )
    client.query(GetUserQuery()).fetchPolicy(FetchPolicy.NetworkOnly).cacheHeaders(receivedDate(currentTimeSeconds())).execute()

    // Read User.email from cache -> it should fail
    userEmailResponse = client.query(GetUserEmailQuery()).fetchPolicy(FetchPolicy.CacheOnly).execute()
    e = userEmailResponse.exception as CacheMissException
    assertTrue(e.stale)
  }

  @Test
  fun isStaleMemory() {
    isStale(MemoryCacheFactory())
  }

  @Test
  fun isStaleSql() {
    isStale(SqlNormalizedCacheFactory())
  }

  @Test
  fun isStaleChainedSql() {
    isStale(MemoryCacheFactory().chain(SqlNormalizedCacheFactory()))
  }

  @Test
  fun isStaleRocksDB() {
    isStale(RocksDBNormalizedCacheFactory("apollo-${Random.nextULong()}"))
  }

  @Test
  fun isStaleChainedRocksDB() {
    isStale(MemoryCacheFactory().chain(RocksDBNormalizedCacheFactory("apollo-${Random.nextULong()}")))
  }

  private fun isStale(normalizedCacheFactory: NormalizedCacheFactory) = runTest {
    val mockServer = MockServer()
    val client = ApolloClient.Builder()
        .normalizedCache(
            normalizedCacheFactory = normalizedCacheFactory,
            cacheKeyGenerator = DefaultCacheKeyGenerator,
            cacheResolver = CacheControlCacheResolver(
                SchemaCoordinatesMaxAgeProvider(
                    mapOf(
                        "User.email" to MaxAge.Duration(2.seconds),
                    ),
                    defaultMaxAge = 20.seconds,
                ),
                delegateResolver = DefaultCacheResolver,
            ),
        )
        .storeExpirationDate(true)
        .serverUrl(mockServer.url())
        .build()
    client.apolloStore.clearAll()

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

    // Store data with an expiration date 10s in the future, and a received date 10s in the past
    mockServer.enqueue(
        MockResponse.Builder()
            .addHeader("Cache-Control", "max-age=10")
            .body(data)
            .build(),
    )
    client.query(GetUserQuery()).fetchPolicy(FetchPolicy.NetworkOnly).cacheHeaders(receivedDate(currentTimeSeconds() - 10)).execute()

    // Read User.name from cache -> it should succeed, and not indicate that it's stale
    val userNameResponse = client.query(GetUserNameQuery()).fetchPolicy(FetchPolicy.CacheOnly).execute()
    assertTrue(userNameResponse.data?.user?.name == "John")
    assertTrue(userNameResponse.cacheInfo?.isStale == false)

    // Read User.email from cache, with a maxStale of 10 -> it should succeed but indicate that it's stale
    var userEmailResponse = client.query(GetUserEmailQuery()).fetchPolicy(FetchPolicy.CacheOnly).maxStale(10.seconds).execute()
    assertTrue(userEmailResponse.data?.user?.email == "john@doe.com")
    assertTrue(userEmailResponse.cacheInfo?.isStale == true)

    // Store data with a slate date of now, and a received date of now
    mockServer.enqueue(
        MockResponse.Builder()
            .addHeader("Cache-Control", "max-age=0")
            .body(data)
            .build(),
    )
    client.query(GetUserQuery()).fetchPolicy(FetchPolicy.NetworkOnly).cacheHeaders(receivedDate(currentTimeSeconds())).execute()

    // Read User.email from cache, with a maxStale of 10 -> it should succeed but indicate that it's stale
    userEmailResponse = client.query(GetUserEmailQuery()).fetchPolicy(FetchPolicy.CacheOnly).maxStale(10.seconds).execute()
    assertTrue(userEmailResponse.data?.user?.email == "john@doe.com")
    assertTrue(userEmailResponse.cacheInfo?.isStale == true)
  }

  @Test
  fun queryNetworkIfStaleMemory() {
    queryNetworkIfStale(MemoryCacheFactory())
  }

  @Test
  fun queryNetworkIfStaleSql() {
    queryNetworkIfStale(SqlNormalizedCacheFactory())
  }

  @Test
  fun queryNetworkIfStaleChainedSql() {
    queryNetworkIfStale(MemoryCacheFactory().chain(SqlNormalizedCacheFactory()))
  }

  @Test
  fun queryNetworkIfStaleRocksDB() {
    queryNetworkIfStale(RocksDBNormalizedCacheFactory("apollo-${Random.nextULong()}"))
  }

  @Test
  fun queryNetworkIfStaleChainedRocksDB() {
    queryNetworkIfStale(MemoryCacheFactory().chain(RocksDBNormalizedCacheFactory("apollo-${Random.nextULong()}")))
  }

  private fun queryNetworkIfStale(normalizedCacheFactory: NormalizedCacheFactory) = runTest {
    val mockServer = MockServer()
    val client = ApolloClient.Builder()
        .normalizedCache(
            cacheKeyGenerator = DefaultCacheKeyGenerator,
            normalizedCacheFactory = normalizedCacheFactory,
            cacheResolver = CacheControlCacheResolver(GlobalMaxAgeProvider(1.days), DefaultCacheResolver),
        )
        .serverUrl(mockServer.url())
        .build()
    client.apolloStore.clearAll()

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

    val query = GetUserQuery()

    // Store data with a received date of now
    mockServer.enqueueString(data)
    client.query(GetUserQuery()).fetchPolicy(FetchPolicy.NetworkOnly)
        .cacheHeaders(receivedDate(currentTimeSeconds()))
        .execute()
    // Should get 1 item from the cache (fresh)
    client.queryNetworkIfStale(query).test {
      val response = awaitItem()
      assertTrue(response.data?.user?.name == "John")
      assertTrue(response.cacheInfo?.isCacheHit == true)
      assertTrue(response.cacheInfo?.isStale == false)
      awaitComplete()
    }

    // Store data with a received date of 1.5 days ago
    mockServer.enqueueString(data)
    client.query(GetUserQuery()).fetchPolicy(FetchPolicy.NetworkOnly)
        .cacheHeaders(receivedDate(currentTimeSeconds() - 1.5.days.inWholeSeconds))
        .execute()
    // Should get 2 items: 1 from the cache (stale within max stale) and 1 from the network
    mockServer.enqueueString(data)
    client.queryNetworkIfStale(query).test {
      val response1 = awaitItem()
      assertTrue(response1.data?.user?.name == "John")
      assertTrue(response1.cacheInfo?.isCacheHit == true)
      assertTrue(response1.cacheInfo?.isStale == true)
      val response2 = awaitItem()
      assertTrue(response2.data?.user?.name == "John")
      assertFalse(response2.isFromCache)
      awaitComplete()
    }

    // Store data with a received date of 2.5 days ago
    mockServer.enqueueString(data)
    client.query(GetUserQuery()).fetchPolicy(FetchPolicy.NetworkOnly)
        .cacheHeaders(receivedDate(currentTimeSeconds() - 2.5.days.inWholeSeconds))
        .execute()
    // Should get 1 item from the network (stale above max stale)
    mockServer.enqueueString(data)
    client.queryNetworkIfStale(query).test {
      val response = awaitItem()
      assertTrue(response.data?.user?.name == "John")
      assertFalse(response.isFromCache)
      awaitComplete()
    }
  }
}

private fun <D : Query.Data> ApolloClient.queryNetworkIfStale(query: Query<D>): Flow<ApolloResponse<D>> = flow {
  val cacheResponse = query(query).fetchPolicy(FetchPolicy.CacheOnly).maxStale(1.days).execute()
  when {
    cacheResponse.exception is CacheMissException -> {
      // Stale above maxStale (or not cached): emit the network response only
      emit(query(query).fetchPolicy(FetchPolicy.NetworkOnly).execute())
    }

    cacheResponse.cacheInfo?.isStale == true -> {
      // Stale within maxStale: emit the cache response and also the network response
      emit(cacheResponse)
      emit(query(query).fetchPolicy(FetchPolicy.NetworkOnly).execute())
    }

    else -> {
      // Fresh: emit the cache response only
      emit(cacheResponse)
    }
  }
}

