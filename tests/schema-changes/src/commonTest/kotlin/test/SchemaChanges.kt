package test

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.CustomScalarAdapters
import com.apollographql.apollo.api.json.jsonReader
import com.apollographql.apollo.exception.CacheMissException
import com.apollographql.cache.normalized.FetchPolicy
import com.apollographql.cache.normalized.api.DefaultCacheKeyGenerator
import com.apollographql.cache.normalized.api.DefaultCacheResolver
import com.apollographql.cache.normalized.fetchPolicy
import com.apollographql.cache.normalized.internal.normalized
import com.apollographql.cache.normalized.memory.MemoryCacheFactory
import com.apollographql.cache.normalized.normalizedCache
import com.apollographql.cache.normalized.testing.runTest
import com.apollographql.mockserver.MockServer
import com.apollographql.mockserver.enqueueString
import okio.Buffer
import okio.use
import schema.changes.GetFieldQuery
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SchemaChangesTest {
  @Test
  fun normalization() = runTest {
    val operation = GetFieldQuery()

    @Suppress("UNUSED_VARIABLE")
    val v1Data = """
      {
        "field": {
          "__typename": "DefaultField",
          "id": "1",
          "name": "Name1"
        }
      }
    """.trimIndent()

    val v2Data = """
      {
        "field": {
          "__typename": "NewField",
          "id": "1",
          "name": "Name1"
        }
      }
    """.trimIndent()

    val data = operation.adapter().fromJson(
        Buffer().writeUtf8(v2Data).jsonReader(),
        CustomScalarAdapters.Empty,
    )

    data.normalized(operation, DefaultCacheKeyGenerator)
  }

  @Test
  fun readingInvalidDataFromCacheIsTreatedAsCacheMiss() = runTest {
    MockServer().use { mockServer ->
      ApolloClient.Builder()
          .serverUrl(mockServer.url())
          .normalizedCache(MemoryCacheFactory(), DefaultCacheKeyGenerator, DefaultCacheResolver)
          .build()
          .use { apolloClient ->
            // Write v1 schema data to the cache
            mockServer.enqueueString(
                // language=JSON
                """
                {
                  "data": {
                    "user": "John"
                  }
                }
                """.trimIndent(),
            )
            apolloClient.query(schemav1.GetUserQuery())
                .fetchPolicy(FetchPolicy.NetworkOnly)
                .execute()

            // Read v2 schema data from the cache
            val cacheResponse = apolloClient.query(schemav2.GetUserQuery())
                .fetchPolicy(FetchPolicy.CacheOnly)
                .execute()
            assertIs<CacheMissException>(cacheResponse.exception)
            assertTrue(cacheResponse.exception!!.message!!.contains("Expected BEGIN_OBJECT but was STRING at path data.user"))
          }
    }
  }
}
