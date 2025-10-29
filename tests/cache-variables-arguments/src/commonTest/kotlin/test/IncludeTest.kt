package test

import cache.include.GetUserQuery
import com.apollographql.apollo.ApolloClient
import com.apollographql.cache.normalized.FetchPolicy
import com.apollographql.cache.normalized.api.DefaultCacheKeyGenerator
import com.apollographql.cache.normalized.api.DefaultCacheResolver
import com.apollographql.cache.normalized.apolloStore
import com.apollographql.cache.normalized.fetchPolicy
import com.apollographql.cache.normalized.memory.MemoryCacheFactory
import com.apollographql.cache.normalized.normalizedCache
import com.apollographql.cache.normalized.testing.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class IncludeTest {
  @Test
  fun simple() = runTest {
    val client = ApolloClient.Builder()
        .normalizedCache(MemoryCacheFactory(), DefaultCacheKeyGenerator, DefaultCacheResolver)
        .serverUrl("https://unused.com")
        .build()

    val operation = GetUserQuery(withDetails = false)

    val data = GetUserQuery.Data(
        user = GetUserQuery.User(__typename = "User", id = "42", userDetails = null)
    )

    client.apolloStore.writeOperation(operation, data)

    val response = client.query(operation).fetchPolicy(FetchPolicy.CacheOnly).execute()

    assertEquals("42", response.data?.user?.id)
    assertEquals(null, response.data?.user?.userDetails)
  }
}
