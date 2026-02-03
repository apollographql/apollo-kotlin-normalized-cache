package test

import app.cash.turbine.test
import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.ApolloResponse
import com.apollographql.apollo.exception.ApolloHttpException
import com.apollographql.apollo.exception.CacheMissException
import com.apollographql.cache.normalized.FetchPolicy
import com.apollographql.cache.normalized.api.CacheControlCacheResolver
import com.apollographql.cache.normalized.api.DefaultCacheResolver
import com.apollographql.cache.normalized.api.GlobalMaxAgeProvider
import com.apollographql.cache.normalized.api.IdCacheKeyGenerator
import com.apollographql.cache.normalized.apolloStore
import com.apollographql.cache.normalized.cacheInfo
import com.apollographql.cache.normalized.fetchPolicy
import com.apollographql.cache.normalized.isFromCache
import com.apollographql.cache.normalized.maxStale
import com.apollographql.cache.normalized.memory.MemoryCacheFactory
import com.apollographql.cache.normalized.normalizedCache
import com.apollographql.cache.normalized.options.cacheMissesAsException
import com.apollographql.cache.normalized.storeReceivedDate
import com.apollographql.mockserver.MockResponse
import com.apollographql.mockserver.MockServer
import declarative.GetUserNameQuery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours


class StaleTest {
  @Test
  fun staleErrorThenNetworkError() = runTest {
    val mockServer = MockServer()
    val apolloClient = ApolloClient.Builder()
        .serverUrl(mockServer.url())
        .storeReceivedDate(true)
        .maxStale(14.days)
        .cacheMissesAsException(true)
        .normalizedCache(
            normalizedCacheFactory = MemoryCacheFactory(),
            cacheKeyGenerator = IdCacheKeyGenerator(),
            cacheResolver = CacheControlCacheResolver(
                maxAgeProvider = GlobalMaxAgeProvider(24.hours),
                delegateResolver = DefaultCacheResolver,
            ),
            enableOptimisticUpdates = true,
        )
        .build()


    apolloClient.apolloStore.writeOperation(
        operation = GetUserNameQuery(),
        data = GetUserNameQuery.Data(
            GetUserNameQuery.User(
                __typename = "User",
                name = "John Doe",
            ),
        ),
        cacheHeaders = receivedDate(currentTimeSeconds() - 15.days.inWholeSeconds),
    )
    mockServer.enqueue(MockResponse.Builder().statusCode(500).body("error").build())

    val response: Flow<ApolloResponse<GetUserNameQuery.Data>> = apolloClient
        .query(GetUserNameQuery())
        .fetchPolicy(FetchPolicy.CacheFirst)
        .toFlow()

    response.test {
      awaitItem().let { item ->
        // First emission: cache error
        assertIs<ApolloResponse<GetUserNameQuery.Data>>(item)
        assertEquals(expected = true, actual = item.cacheInfo?.isStale)
        assertNull(item.data)
        assertNotNull(item.exception)
        assertIs<CacheMissException>(item.exception)
        assertFalse(item.isLast)
        assertEquals(expected = true, actual = item.isFromCache)
      }
      awaitItem().let { item ->
        // First emission: network error
        assertIs<ApolloResponse<GetUserNameQuery.Data>>(item)
        assertEquals(expected = false, actual = item.cacheInfo?.isStale)
        assertNull(item.data)
        assertNotNull(item.exception)
        assertIs<ApolloHttpException>(item.exception)
        assertTrue(item.isLast)
        assertEquals(expected = false, actual = item.isFromCache)
      }
      awaitComplete()
    }
  }
}
