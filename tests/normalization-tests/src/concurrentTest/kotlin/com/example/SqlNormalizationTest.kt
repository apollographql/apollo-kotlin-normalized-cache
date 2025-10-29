package com.example

import com.apollographql.apollo.ApolloClient
import com.apollographql.cache.normalized.FetchPolicy
import com.apollographql.cache.normalized.allRecords
import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.api.DefaultCacheKeyGenerator
import com.apollographql.cache.normalized.api.DefaultCacheResolver
import com.apollographql.cache.normalized.apolloStore
import com.apollographql.cache.normalized.fetchPolicy
import com.apollographql.cache.normalized.normalizedCache
import com.apollographql.cache.normalized.testing.SqlNormalizedCacheFactory
import com.apollographql.cache.normalized.testing.append
import com.apollographql.cache.normalized.testing.runTest
import com.apollographql.mockserver.MockServer
import com.apollographql.mockserver.enqueueString
import com.example.four.CreateUserMutation
import kotlin.test.Test
import kotlin.test.assertContentEquals

class SqlNormalizationTest {
  @Test
  fun mutationRoot() = runTest {
    val mockserver = MockServer()
    val apolloClient = ApolloClient.Builder()
        .serverUrl(mockserver.url())
        .normalizedCache(SqlNormalizedCacheFactory(), cacheKeyGenerator = DefaultCacheKeyGenerator, cacheResolver = DefaultCacheResolver)
        .build()

    apolloClient.apolloStore.clearAll()
    mockserver.enqueueString(
        // language=JSON
        """
        {
          "data": {
            "createUser": {
              "__typename": "User",
              "id": "user-1",
              "name": "John Doe",
              "projects": [
                {
                  "__typename": "Project",
                  "id": "project-1",
                  "name": "Project 1",
                  "description": "Description 1",
                  "owner": {
                    "__typename": "User",
                    "id": "user-2",
                    "name": "Jane Doe"
                  }
                }
              ]
            }
          }
        }
        """.trimIndent()
    )
    apolloClient.mutation(CreateUserMutation("John")).fetchPolicy(FetchPolicy.NetworkOnly).execute()

    apolloClient.apolloStore.accessCache { normalizedCache ->
      assertContentEquals(
          listOf(
              CacheKey.MUTATION_ROOT,
              CacheKey.MUTATION_ROOT.append("""createUser({"name":"John"})"""),
              CacheKey.MUTATION_ROOT.append("""createUser({"name":"John"})""", "projects", "0"),
              CacheKey.MUTATION_ROOT.append("""createUser({"name":"John"})""", "projects", "0", "owner"),
          ),
          normalizedCache.allRecords().keys,
      )
    }

    apolloClient.close()
    mockserver.close()
  }
}
