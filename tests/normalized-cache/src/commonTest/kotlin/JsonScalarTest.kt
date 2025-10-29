package test

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.AnyAdapter
import com.apollographql.cache.normalized.CacheManager
import com.apollographql.cache.normalized.FetchPolicy
import com.apollographql.cache.normalized.api.DefaultCacheKeyGenerator
import com.apollographql.cache.normalized.api.DefaultCacheResolver
import com.apollographql.cache.normalized.cacheManager
import com.apollographql.cache.normalized.fetchPolicy
import com.apollographql.cache.normalized.memory.MemoryCacheFactory
import com.apollographql.cache.normalized.testing.runTest
import com.apollographql.mockserver.MockServer
import com.apollographql.mockserver.enqueueString
import fixtures.JsonScalar
import fixtures.JsonScalarModified
import normalizer.GetJsonScalarQuery
import normalizer.type.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class JsonScalarTest {
  private lateinit var mockServer: MockServer
  private lateinit var apolloClient: ApolloClient
  private lateinit var cacheManager: CacheManager

  private suspend fun setUp() {
    cacheManager = CacheManager(MemoryCacheFactory(), cacheKeyGenerator = DefaultCacheKeyGenerator, cacheResolver = DefaultCacheResolver)
    mockServer = MockServer()
    apolloClient = ApolloClient.Builder().serverUrl(mockServer.url())
        .cacheManager(cacheManager)
        .addCustomScalarAdapter(Json.type, AnyAdapter)
        .build()
  }

  private suspend fun tearDown() {
    mockServer.close()
  }

  // see https://github.com/apollographql/apollo-kotlin/issues/2854
  @Test
  fun jsonScalar() = runTest(before = { setUp() }, after = { tearDown() }) {
    mockServer.enqueueString(JsonScalar)
    var response = apolloClient.query(GetJsonScalarQuery()).execute()

    assertFalse(response.hasErrors())
    var expectedMap = mapOf(
        "obj" to mapOf("key" to "value"),
        "list" to listOf(0, 1, 2)
    )
    assertEquals(expectedMap, response.data!!.json)

    /**
     * Update the json value, it should be replaced, not merged
     */
    mockServer.enqueueString(JsonScalarModified)
    apolloClient.query(GetJsonScalarQuery()).fetchPolicy(FetchPolicy.NetworkFirst).execute()
    response = apolloClient.query(GetJsonScalarQuery()).fetchPolicy(FetchPolicy.CacheOnly).execute()

    assertFalse(response.hasErrors())

    expectedMap = mapOf(
        "obj" to mapOf("key2" to "value2"),
    )
    assertEquals(expectedMap, response.data!!.json)
  }
}
