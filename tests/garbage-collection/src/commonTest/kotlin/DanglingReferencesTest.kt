package test

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.testing.internal.runTest
import com.apollographql.cache.normalized.ApolloStore
import com.apollographql.cache.normalized.FetchPolicy
import com.apollographql.cache.normalized.allRecords
import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.api.fieldKey
import com.apollographql.cache.normalized.fetchPolicy
import com.apollographql.cache.normalized.memory.MemoryCacheFactory
import com.apollographql.cache.normalized.removeDanglingReferences
import com.apollographql.cache.normalized.sql.SqlNormalizedCacheFactory
import com.apollographql.cache.normalized.store
import com.apollographql.mockserver.MockServer
import com.apollographql.mockserver.enqueueString
import kotlinx.coroutines.test.TestResult
import okio.use
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DanglingReferencesTest {
  @Test
  fun simpleMemory() = simple(ApolloStore(MemoryCacheFactory()))

  @Test
  fun simpleSql() = simple(ApolloStore(SqlNormalizedCacheFactory()))

  @Test
  fun simpleChained(): TestResult {
    return simple(ApolloStore(MemoryCacheFactory().chain(SqlNormalizedCacheFactory())))
  }

  private fun simple(apolloStore: ApolloStore) = runTest {
    val mockServer = MockServer()
    val store = apolloStore.also { it.clearAll() }
    ApolloClient.Builder()
        .serverUrl(mockServer.url())
        .store(store)
        .build()
        .use { apolloClient ->
          mockServer.enqueueString(REPOSITORY_LIST_RESPONSE)
          apolloClient.query(RepositoryListQuery())
              .fetchPolicy(FetchPolicy.NetworkOnly)
              .execute()

          var allRecords = store.accessCache { it.allRecords() }
          assertTrue(allRecords[CacheKey("Repository:0")]!!.fields.containsKey("starGazers"))

          // Remove User 1, now Repository 0.starGazers is a dangling reference
          store.remove(CacheKey("User:1"), cascade = false)
          val removedFieldsAndRecords = store.removeDanglingReferences()
          assertEquals(
              setOf(CacheKey("Repository:0").fieldKey("starGazers")),
              removedFieldsAndRecords.removedFields
          )
          assertEquals(
              emptySet(),
              removedFieldsAndRecords.removedRecords
          )
          allRecords = store.accessCache { it.allRecords() }
          assertFalse(allRecords[CacheKey("Repository:0")]!!.fields.containsKey("starGazers"))
        }
  }

  @Test
  fun multipleMemory() = multiple(ApolloStore(MemoryCacheFactory()))

  @Test
  fun multipleSql() = multiple(ApolloStore(SqlNormalizedCacheFactory()))

  @Test
  fun multipleChained() = multiple(ApolloStore(MemoryCacheFactory().chain(SqlNormalizedCacheFactory())))

  private fun multiple(apolloStore: ApolloStore) = runTest {
    val mockServer = MockServer()
    val store = apolloStore.also { it.clearAll() }
    ApolloClient.Builder()
        .serverUrl(mockServer.url())
        .store(store)
        .build()
        .use { apolloClient ->
          mockServer.enqueueString(META_PROJECT_LIST_RESPONSE)
          apolloClient.query(MetaProjectListQuery())
              .fetchPolicy(FetchPolicy.NetworkOnly)
              .execute()

          // Remove User 0
          // thus (metaProjects.0.0.type).owners is a dangling reference
          // thus (metaProjects.0.0.type) is empty and removed
          // thus (metaProjects.0.0).type is a dangling reference
          // thus (metaProjects.0.0) is empty and removed
          // thus (QUERY_ROOT).metaProjects is a dangling reference
          // thus QUERY_ROOT is empty and removed
          store.remove(CacheKey("User:0"), cascade = false)
          val removedFieldsAndRecords = store.removeDanglingReferences()
          assertEquals(
              setOf(
                  CacheKey(CacheKey("metaProjects.0.0").fieldKey("type")).fieldKey("owners"),
                  CacheKey("metaProjects.0.0").fieldKey("type"),
                  CacheKey("QUERY_ROOT").fieldKey("metaProjects"),
              ),
              removedFieldsAndRecords.removedFields
          )
          assertEquals(
              setOf(
                  CacheKey(CacheKey("metaProjects.0.0").fieldKey("type")),
                  CacheKey("metaProjects.0.0"),
                  CacheKey("QUERY_ROOT"),
              ),
              removedFieldsAndRecords.removedRecords
          )
          val allRecords = store.accessCache { it.allRecords() }
          assertFalse(allRecords.containsKey(CacheKey("QUERY_ROOT")))
          assertFalse(allRecords.containsKey(CacheKey("metaProjects.0.0")))
          assertFalse(allRecords.containsKey(CacheKey(CacheKey("metaProjects.0.0").fieldKey("type"))))
        }
  }

  // language=JSON
  private val REPOSITORY_LIST_RESPONSE = """
  {
    "data": {
      "repositories": [
        {
          "__typename": "Repository",
          "id": "0",
          "stars": 10,
          "starGazers": [
            {
              "__typename": "User",
              "id": "0",
              "name": "John"
            },
            {
              "__typename": "User",
              "id": "1",
              "name": "Jane"
            }
          ]
        },
        {
          "__typename": "Repository",
          "id": "1",
          "stars": 20,
          "starGazers": [
            {
              "__typename": "User",
              "id": "0",
              "name": "John"
            },
            {
              "__typename": "User",
              "id": "2",
              "name": "Alice"
            }
          ]
        }
      ]
    }
  }
  """.trimIndent()

  // language=JSON
  private val META_PROJECT_LIST_RESPONSE = """
  {
    "data": {
      "metaProjects": [
        [
          {
            "__typename": "Project",
            "type": {
              "__typename": "ProjectType",
              "owners": [
                {
                  "__typename": "User",
                  "id": "0",
                  "name": "User 0"
                }
              ]
            }
          },
          {
            "__typename": "Project",
            "type": {
              "__typename": "ProjectType",
              "owners": [
                {
                  "__typename": "User",
                  "id": "1",
                  "name": "User 1"
                }
              ]
            }
          }
        ],
        [
          {
            "__typename": "Project",
            "type": {
              "__typename": "ProjectType",
              "owners": [
                {
                  "__typename": "User",
                  "id": "2",
                  "name": "User 2"
                }
              ]
            }
          }
        ]
      ]
    }
  }
  """.trimIndent()
}
