package test

import app.cash.turbine.test
import app.cash.turbine.withTurbineTimeout
import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.ApolloRequest
import com.apollographql.apollo.api.ApolloResponse
import com.apollographql.apollo.api.Error
import com.apollographql.apollo.api.Operation
import com.apollographql.apollo.exception.ApolloGraphQLException
import com.apollographql.apollo.exception.CacheMissException
import com.apollographql.apollo.interceptor.ApolloInterceptor
import com.apollographql.apollo.interceptor.ApolloInterceptorChain
import com.apollographql.cache.normalized.api.CacheHeaders
import com.apollographql.cache.normalized.api.NormalizedCache
import com.apollographql.cache.normalized.api.NormalizedCacheFactory
import com.apollographql.cache.normalized.api.Record
import com.apollographql.cache.normalized.api.RecordMerger
import com.apollographql.cache.normalized.fetchFromCache
import com.apollographql.cache.normalized.isFromCache
import com.apollographql.cache.normalized.memory.MemoryCacheFactory
import com.apollographql.cache.normalized.refetchPolicyInterceptor
import com.apollographql.cache.normalized.testing.assertErrorsEquals
import com.apollographql.cache.normalized.testing.runTest
import com.apollographql.cache.normalized.watch
import com.apollographql.mockserver.MockRequestBase
import com.apollographql.mockserver.MockResponse
import com.apollographql.mockserver.MockServer
import com.apollographql.mockserver.MockServerHandler
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.single
import okio.use
import test.cache.Cache.cache
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class FetchPolicyTest {
  @Test
  fun writeToCacheAsyncWithRefetchPolicyCacheFirstAndServerErrors() = runTest {
    MockServer.Builder().handler(
        object : MockServerHandler {
          override fun handle(request: MockRequestBase): MockResponse {
            return MockResponse.Builder()
                .body(
                    // language=JSON
                    """
                    {
                      "errors": [
                        {
                          "message": "Can't compute lastName",
                          "path": [
                            "me",
                            "lastName"
                          ]
                        }
                      ],
                      "data": {
                        "me": {
                          "__typename": "User",
                          "id": "1",
                          "firstName": "${Random.nextInt()}",
                          "lastName": null
                        }
                      }
                    }
                    """.trimIndent()
                )
                .build()
          }
        }
    ).build().use { mockServer ->
      ApolloClient.Builder()
          .serverUrl(mockServer.url())
          .cache(AsyncCacheFactory(), writeToCacheAsynchronously = true)
          .build()
          .use { apolloClient ->
            apolloClient.query(MeQuery())
                .refetchPolicyInterceptor(PartialCacheFirstInterceptor)
                .watch()
                .test {
                  // 1. response from the cache (cache miss)
                  val cacheResponse1 = awaitItem()
                  assertTrue(cacheResponse1.isFromCache)
                  assertIs<CacheMissException>(cacheResponse1.exception)

                  // 2. response from the network, with the error
                  val networkResponse1 = awaitItem()
                  assertFalse(networkResponse1.isFromCache)
                  assertErrorsEquals(
                      listOf(Error.Builder("Can't compute lastName").path(listOf("me", "lastName")).build()),
                      networkResponse1.errors
                  )

                  // 3. with writeToCacheAsynchronously, when the network response is written to the cache, the watcher gets notified with the cache response
                  val cacheResponse2 = awaitItem()
                  assertTrue(cacheResponse2.isFromCache)
                  // GraphQL error is surfaced as an exception by default (serverErrorsAsException is true)
                  assertIs<ApolloGraphQLException>(cacheResponse2.exception)

                  // That wasn't a cache miss: expect no more emissions
                  withTurbineTimeout(200.milliseconds) {
                    assertFails { awaitItem() }
                  }

                  cancelAndIgnoreRemainingEvents()
                }
          }
    }
  }
}

/**
 * A cache that simulates slow writes.
 * This removes flakiness by ensuring that when using `writeToCacheAsynchronously = true`, there is enough time to start observing the
 * cache, before writing happens.
 */
private fun AsyncCacheFactory(): NormalizedCacheFactory = object : NormalizedCacheFactory() {
  override fun create(): NormalizedCache {
    val wrapped = MemoryCacheFactory().create()
    return object : NormalizedCache by wrapped {
      override suspend fun merge(
          records: Collection<Record>,
          cacheHeaders: CacheHeaders,
          recordMerger: RecordMerger,
      ): Set<String> {
        delay(100.milliseconds)
        return wrapped.merge(records, cacheHeaders, recordMerger)
      }
    }
  }
}

/**
 * An interceptor that emits the response from the cache first, and if there was a cache miss on the response, emits the response(s) from
 * the network.
 * If there are no exception on the cache response or there is an exception which is not a cache miss (server error), no network request is
 * made.
 */
val PartialCacheFirstInterceptor = object : ApolloInterceptor {
  override fun <D : Operation.Data> intercept(request: ApolloRequest<D>, chain: ApolloInterceptorChain): Flow<ApolloResponse<D>> {
    return flow {
      val cacheResponse = chain.proceed(
          request = request
              .newBuilder()
              .fetchFromCache(true)
              .build(),
      ).single()
      val isCacheMiss = cacheResponse.exception == CacheMissException
      emit(cacheResponse.newBuilder().isLast(!isCacheMiss).build())
      if (isCacheMiss) {
        emitAll(chain.proceed(request = request))
      }
    }
  }
}
