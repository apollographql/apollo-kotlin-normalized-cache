package test

import com.apollographql.apollo.ApolloClient
import com.apollographql.cache.normalized.CacheManager
import com.apollographql.cache.normalized.FetchPolicy
import com.apollographql.cache.normalized.allRecords
import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.api.FieldPolicyCacheResolver
import com.apollographql.cache.normalized.api.SchemaCoordinatesMaxAgeProvider
import com.apollographql.cache.normalized.api.TypePolicyCacheKeyGenerator
import com.apollographql.cache.normalized.apolloStore
import com.apollographql.cache.normalized.cacheHeaders
import com.apollographql.cache.normalized.cacheManager
import com.apollographql.cache.normalized.fetchPolicy
import com.apollographql.cache.normalized.garbageCollect
import com.apollographql.cache.normalized.memory.MemoryCacheFactory
import com.apollographql.cache.normalized.testing.SqlNormalizedCacheFactory
import com.apollographql.cache.normalized.testing.append
import com.apollographql.cache.normalized.testing.fieldKey
import com.apollographql.cache.normalized.testing.runTest
import com.apollographql.mockserver.MockServer
import com.apollographql.mockserver.enqueueString
import okio.use
import test.cache.Cache
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class GarbageCollectTest {
  @Test
  fun garbageCollectMemory() =
    garbageCollect(CacheManager(MemoryCacheFactory(), cacheKeyGenerator = TypePolicyCacheKeyGenerator(Cache.typePolicies), cacheResolver = FieldPolicyCacheResolver(Cache.fieldPolicies)))

  @Test
  fun garbageCollectSql() =
    garbageCollect(CacheManager(SqlNormalizedCacheFactory(), cacheKeyGenerator = TypePolicyCacheKeyGenerator(Cache.typePolicies), cacheResolver = FieldPolicyCacheResolver(Cache.fieldPolicies)))

  @Test
  fun garbageCollectChained() =
    garbageCollect(CacheManager(MemoryCacheFactory().chain(SqlNormalizedCacheFactory()), cacheKeyGenerator = TypePolicyCacheKeyGenerator(Cache.typePolicies), cacheResolver = FieldPolicyCacheResolver(Cache.fieldPolicies)))

  private fun garbageCollect(cacheManager: CacheManager) = runTest {
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
              .cacheHeaders(receivedDate(currentTimeSeconds() - 90))
              .execute()

          // (metaProjects.0.0.type).owners, (metaProjects.0.1.type).owners, (metaProjects.1.0.type).owners are stale
          // thus (metaProjects.0.0.type), (metaProjects.0.1.type), (metaProjects.1.0.type) are empty and removed
          // thus (metaProjects.0.0).type, (metaProjects.0.1).type, (metaProjects.1.0).type are dangling references
          // thus (metaProjects.0.0), (metaProjects.0.1), (metaProjects.1.0) are empty and removed
          // thus (QUERY_ROOT).metaProjects is a dangling reference
          // thus QUERY_ROOT is empty and removed
          // every other record is unreachable and removed
          val maxAgeProvider = SchemaCoordinatesMaxAgeProvider(
              Cache.maxAges,
              defaultMaxAge = 120.seconds,
          )
          val garbageCollectResult = apolloClient.apolloStore.garbageCollect(maxAgeProvider)
          assertEquals(
              setOf(
                  CacheKey("metaProjects").append("0", "0", "type").fieldKey("owners"),
                  CacheKey("metaProjects").append("0", "1", "type").fieldKey("owners"),
                  CacheKey("metaProjects").append("1", "0", "type").fieldKey("owners"),
              ),
              garbageCollectResult.removedStaleFields.removedFields
          )
          assertEquals(
              setOf(
                  CacheKey("metaProjects").append("0", "0", "type"),
                  CacheKey("metaProjects").append("0", "1", "type"),
                  CacheKey("metaProjects").append("1", "0", "type"),
              ),
              garbageCollectResult.removedStaleFields.removedRecords
          )

          assertEquals(
              setOf(
                  CacheKey("metaProjects").append("0", "0").fieldKey("type"),
                  CacheKey("metaProjects").append("0", "1").fieldKey("type"),
                  CacheKey("metaProjects").append("1", "0").fieldKey("type"),
                  CacheKey("QUERY_ROOT").fieldKey("metaProjects"),
              ),
              garbageCollectResult.removedDanglingReferences.removedFields
          )
          assertEquals(
              setOf(
                  CacheKey("metaProjects").append("0", "0"),
                  CacheKey("metaProjects").append("0", "1"),
                  CacheKey("metaProjects").append("1", "0"),
                  CacheKey("QUERY_ROOT"),
              ),
              garbageCollectResult.removedDanglingReferences.removedRecords
          )

          assertEquals(
              setOf(
                  CacheKey("User:0"),
                  CacheKey("User:1"),
                  CacheKey("User:2"),
              ),
              garbageCollectResult.removedUnreachableRecords
          )

          val allRecords = cacheManager.accessCache { it.allRecords() }
          assertEquals(emptyMap(), allRecords)
        }
  }

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
