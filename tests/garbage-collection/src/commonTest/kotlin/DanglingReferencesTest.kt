package test

import com.apollographql.apollo.ApolloClient
import com.apollographql.cache.normalized.CacheManager
import com.apollographql.cache.normalized.FetchPolicy
import com.apollographql.cache.normalized.allRecords
import com.apollographql.cache.normalized.api.CacheHeaders
import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.api.DefaultRecordMerger
import com.apollographql.cache.normalized.api.FieldPolicyCacheResolver
import com.apollographql.cache.normalized.api.Record
import com.apollographql.cache.normalized.api.TypePolicyCacheKeyGenerator
import com.apollographql.cache.normalized.apolloStore
import com.apollographql.cache.normalized.cacheManager
import com.apollographql.cache.normalized.fetchPolicy
import com.apollographql.cache.normalized.memory.MemoryCacheFactory
import com.apollographql.cache.normalized.removeDanglingReferences
import com.apollographql.cache.normalized.testing.SqlNormalizedCacheFactory
import com.apollographql.cache.normalized.testing.append
import com.apollographql.cache.normalized.testing.fieldKey
import com.apollographql.cache.normalized.testing.runTest
import com.apollographql.mockserver.MockServer
import com.apollographql.mockserver.enqueueString
import kotlinx.coroutines.test.TestResult
import okio.use
import test.cache.Cache
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DanglingReferencesTest {
  @Test
  fun simpleMemory() =
    simple(CacheManager(MemoryCacheFactory(), cacheKeyGenerator = TypePolicyCacheKeyGenerator(Cache.typePolicies), cacheResolver = FieldPolicyCacheResolver(Cache.fieldPolicies)))

  @Test
  fun simpleSql() =
    simple(CacheManager(SqlNormalizedCacheFactory(), cacheKeyGenerator = TypePolicyCacheKeyGenerator(Cache.typePolicies), cacheResolver = FieldPolicyCacheResolver(Cache.fieldPolicies)))

  @Test
  fun simpleChained(): TestResult {
    return simple(CacheManager(MemoryCacheFactory().chain(SqlNormalizedCacheFactory()), cacheKeyGenerator = TypePolicyCacheKeyGenerator(Cache.typePolicies), cacheResolver = FieldPolicyCacheResolver(Cache.fieldPolicies)))
  }

  private fun simple(cacheManager: CacheManager) = runTest {
    val mockServer = MockServer()
    cacheManager.clearAll()
    ApolloClient.Builder()
        .serverUrl(mockServer.url())
        .cacheManager(cacheManager)
        .build()
        .use { apolloClient ->
          mockServer.enqueueString(REPOSITORY_LIST_RESPONSE)
          apolloClient.query(RepositoryListQuery())
              .fetchPolicy(FetchPolicy.NetworkOnly)
              .execute()

          var allRecords = cacheManager.accessCache { it.allRecords() }
          assertTrue(allRecords[CacheKey("Repository:0")]!!.fields.containsKey("starGazers"))

          // Remove User 1, now Repository 0.starGazers is a dangling reference
          cacheManager.remove(CacheKey("User:1"), cascade = false)
          val removedFieldsAndRecords = apolloClient.apolloStore.removeDanglingReferences()
          assertEquals(
              setOf(CacheKey("Repository:0").fieldKey("starGazers")),
              removedFieldsAndRecords.removedFields,
          )
          assertEquals(
              emptySet(),
              removedFieldsAndRecords.removedRecords,
          )
          allRecords = cacheManager.accessCache { it.allRecords() }
          assertFalse(allRecords[CacheKey("Repository:0")]!!.fields.containsKey("starGazers"))
        }
  }

  @Test
  fun multipleMemory() =
    multiple(CacheManager(MemoryCacheFactory(), cacheKeyGenerator = TypePolicyCacheKeyGenerator(Cache.typePolicies), cacheResolver = FieldPolicyCacheResolver(Cache.fieldPolicies)))

  @Test
  fun multipleSql() =
    multiple(CacheManager(SqlNormalizedCacheFactory(), cacheKeyGenerator = TypePolicyCacheKeyGenerator(Cache.typePolicies), cacheResolver = FieldPolicyCacheResolver(Cache.fieldPolicies)))

  @Test
  fun multipleChained() =
    multiple(CacheManager(MemoryCacheFactory().chain(SqlNormalizedCacheFactory()), cacheKeyGenerator = TypePolicyCacheKeyGenerator(Cache.typePolicies), cacheResolver = FieldPolicyCacheResolver(Cache.fieldPolicies)))

  private fun multiple(cacheManager: CacheManager) = runTest {
    val mockServer = MockServer()
    cacheManager.clearAll()
    ApolloClient.Builder()
        .serverUrl(mockServer.url())
        .cacheManager(cacheManager)
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
          cacheManager.remove(CacheKey("User:0"), cascade = false)
          val removedFieldsAndRecords = apolloClient.apolloStore.removeDanglingReferences()
          assertEquals(
              setOf(
                  CacheKey("metaProjects").append("0", "0", "type").fieldKey("owners"),
                  CacheKey("metaProjects").append("0", "0").fieldKey("type"),
                  CacheKey("QUERY_ROOT").fieldKey("metaProjects"),
              ),
              removedFieldsAndRecords.removedFields,
          )
          assertEquals(
              setOf(
                  CacheKey("metaProjects").append("0", "0", "type"),
                  CacheKey("metaProjects").append("0", "0"),
                  CacheKey("QUERY_ROOT"),
              ),
              removedFieldsAndRecords.removedRecords,
          )
          val allRecords = cacheManager.accessCache { it.allRecords() }
          assertFalse(allRecords.containsKey(CacheKey("QUERY_ROOT")))
          assertFalse(allRecords.containsKey(CacheKey("metaProjects").append("0", "0")))
          assertFalse(allRecords.containsKey(CacheKey("metaProjects").append("0", "0", "type")))
        }
  }

  @Test
  fun deepMemory() =
    deep(CacheManager(MemoryCacheFactory(), cacheKeyGenerator = TypePolicyCacheKeyGenerator(Cache.typePolicies), cacheResolver = FieldPolicyCacheResolver(Cache.fieldPolicies)))

  @Test
  fun deepSql() =
    deep(CacheManager(SqlNormalizedCacheFactory(), cacheKeyGenerator = TypePolicyCacheKeyGenerator(Cache.typePolicies), cacheResolver = FieldPolicyCacheResolver(Cache.fieldPolicies)))

  @Test
  fun deepChained() =
    deep(CacheManager(MemoryCacheFactory().chain(SqlNormalizedCacheFactory()), cacheKeyGenerator = TypePolicyCacheKeyGenerator(Cache.typePolicies), cacheResolver = FieldPolicyCacheResolver(Cache.fieldPolicies)))

  private fun deep(cacheManager: CacheManager) = runTest {
    val chainLength = 50
    cacheManager.clearAll()
    cacheManager.accessCache { cache ->
      val chain = buildList {
        for (i in 0..<chainLength) {
          val ref = if (i == chainLength - 1) CacheKey("missing") else CacheKey("R${i + 1}")
          add(Record(key = CacheKey("R$i"), fields = mapOf("__typename" to "T", "next" to ref)))
        }
      }
      cache.merge(chain, cacheHeaders = CacheHeaders.NONE, recordMerger = DefaultRecordMerger)
      val result = cache.removeDanglingReferences(batchSize = 1)
      assertEquals((0..<chainLength).map { CacheKey("R$it").fieldKey("next") }.toSet(), result.removedFields)
      assertEquals((0..<chainLength).map { CacheKey("R$it") }.toSet(), result.removedRecords)
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
