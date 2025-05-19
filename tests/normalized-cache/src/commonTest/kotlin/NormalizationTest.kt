package test

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.testing.QueueTestNetworkTransport
import com.apollographql.apollo.testing.enqueueTestResponse
import com.apollographql.cache.normalized.FetchPolicy
import com.apollographql.cache.normalized.fetchPolicy
import com.apollographql.cache.normalized.memory.MemoryCacheFactory
import com.apollographql.cache.normalized.normalizedCache
import com.apollographql.cache.normalized.testing.runTest
import main.RepositoryListQuery
import kotlin.test.Test
import kotlin.test.assertEquals

class NormalizationTest {
  @Test
  fun variableDefaultValuesTest() = runTest {
    val apolloClient = ApolloClient.Builder()
      .networkTransport(QueueTestNetworkTransport())
      .normalizedCache(MemoryCacheFactory())
      .build()
    val query = RepositoryListQuery()
    apolloClient.enqueueTestResponse(
      query,
      RepositoryListQuery.Data(
          listOf(RepositoryListQuery.Repository(__typename = "Repository", id = "42"))
      )
    )
    apolloClient.query(query).execute()
    val response = apolloClient
      .query(query)
      .fetchPolicy(FetchPolicy.CacheOnly)
      .execute()
    assertEquals("42", response.data!!.repositories.first().id)
  }
}
