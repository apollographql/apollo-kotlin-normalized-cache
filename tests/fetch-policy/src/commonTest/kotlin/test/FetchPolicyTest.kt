package test

import com.apollographql.apollo.ApolloClient
import com.apollographql.cache.normalized.FetchPolicy.CacheFirst
import com.apollographql.cache.normalized.isFromCache
import com.apollographql.cache.normalized.memory.MemoryCacheFactory
import com.apollographql.cache.normalized.refetchPolicy
import com.apollographql.cache.normalized.testing.runTest
import com.apollographql.cache.normalized.watch
import com.apollographql.mockserver.MockServer
import com.apollographql.mockserver.enqueueString
import okio.use
import test.cache.Cache.cache
import kotlin.test.Test

class FetchPolicyTest {
  private lateinit var mockServer: MockServer

  private fun setUp() {
    mockServer = MockServer()
  }

  private fun tearDown() {
    mockServer.close()
  }

  @Test
  fun refetchPolicyCacheFirstWithWriteToCacheAsync() = runTest(before = { setUp() }, after = { tearDown() }) {
    repeat(10) {
      mockServer.enqueueString(
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
              "firstName": "John",
              "lastName": null
            }
          }
        }
        """.trimIndent(),
      )
    }
    ApolloClient.Builder()
        .serverUrl(mockServer.url())
        .cache(MemoryCacheFactory(), writeToCacheAsynchronously = true)
        .build()
        .use { apolloClient ->
          apolloClient.query(MeQuery())
              .refetchPolicy(CacheFirst)
              .watch()
              .collect { response ->
                println(response.toString() + " isFromCache=" + response.isFromCache)
              }
        }
  }
}
