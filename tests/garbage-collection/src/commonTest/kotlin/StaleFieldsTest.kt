package test

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.mpp.currentTimeMillis
import com.apollographql.cache.normalized.CacheManager
import com.apollographql.cache.normalized.FetchPolicy
import com.apollographql.cache.normalized.allRecords
import com.apollographql.cache.normalized.api.ApolloCacheHeaders
import com.apollographql.cache.normalized.api.CacheHeaders
import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.api.GlobalMaxAgeProvider
import com.apollographql.cache.normalized.api.SchemaCoordinatesMaxAgeProvider
import com.apollographql.cache.normalized.apolloStore
import com.apollographql.cache.normalized.cacheHeaders
import com.apollographql.cache.normalized.cacheManager
import com.apollographql.cache.normalized.fetchPolicy
import com.apollographql.cache.normalized.memory.MemoryCacheFactory
import com.apollographql.cache.normalized.removeStaleFields
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class StaleFieldsTest {
  @Test
  fun clientControlledRemoveFieldsMemory() = clientControlledRemoveFields(CacheManager(MemoryCacheFactory()))

  @Test
  fun clientControlledRemoveFieldsSql() = clientControlledRemoveFields(CacheManager(SqlNormalizedCacheFactory()))

  @Test
  fun clientControlledRemoveFieldsChained() =
    clientControlledRemoveFields(CacheManager(MemoryCacheFactory().chain(SqlNormalizedCacheFactory())))

  private fun clientControlledRemoveFields(cacheManager: CacheManager) = runTest {
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
              .cacheHeaders(receivedDate(currentTimeSeconds() - 60))
              .execute()

          var allRecords = cacheManager.accessCache { it.allRecords() }
          assertTrue(allRecords[CacheKey("Repository:0")]!!.fields.containsKey("stars"))
          assertTrue(allRecords[CacheKey("Repository:0")]!!.fields.containsKey("starGazers"))
          assertTrue(allRecords[CacheKey("Repository:1")]!!.fields.containsKey("stars"))
          assertTrue(allRecords[CacheKey("Repository:1")]!!.fields.containsKey("starGazers"))

          val maxAgeProvider = SchemaCoordinatesMaxAgeProvider(
              Cache.maxAges,
              defaultMaxAge = 120.seconds,
          )
          var removedFieldsAndRecords = apolloClient.apolloStore.removeStaleFields(maxAgeProvider)
          // Repository.stars has a max age of 60 seconds, so they should be removed / User has a max age of 90 seconds, so Repository.starGazers should be kept
          assertEquals(
              setOf(
                  CacheKey("Repository:0").fieldKey("stars"),
                  CacheKey("Repository:1").fieldKey("stars"),
              ), removedFieldsAndRecords.removedFields
          )
          assertEquals(
              emptySet(), removedFieldsAndRecords.removedRecords
          )
          allRecords = cacheManager.accessCache { it.allRecords() }
          assertFalse(allRecords[CacheKey("Repository:0")]!!.fields.containsKey("stars"))
          assertTrue(allRecords[CacheKey("Repository:0")]!!.fields.containsKey("starGazers"))
          assertFalse(allRecords[CacheKey("Repository:1")]!!.fields.containsKey("stars"))
          assertTrue(allRecords[CacheKey("Repository:1")]!!.fields.containsKey("starGazers"))

          mockServer.enqueueString(REPOSITORY_LIST_RESPONSE)
          apolloClient.query(RepositoryListQuery())
              .fetchPolicy(FetchPolicy.NetworkOnly)
              .cacheHeaders(receivedDate(currentTimeSeconds() - 90))
              .execute()
          removedFieldsAndRecords = apolloClient.apolloStore.removeStaleFields(maxAgeProvider)
          // Repository.stars and Repository.starGazers should be removed
          assertEquals(
              setOf(
                  CacheKey("Repository:0").fieldKey("stars"),
                  CacheKey("Repository:0").fieldKey("starGazers"),
                  CacheKey("Repository:1").fieldKey("stars"),
                  CacheKey("Repository:1").fieldKey("starGazers"),
              ), removedFieldsAndRecords.removedFields
          )
          assertEquals(
              emptySet(), removedFieldsAndRecords.removedRecords
          )
          allRecords = cacheManager.accessCache { it.allRecords() }
          assertFalse(allRecords[CacheKey("Repository:0")]!!.fields.containsKey("stars"))
          assertFalse(allRecords[CacheKey("Repository:0")]!!.fields.containsKey("starGazers"))
          assertFalse(allRecords[CacheKey("Repository:1")]!!.fields.containsKey("stars"))
          assertFalse(allRecords[CacheKey("Repository:1")]!!.fields.containsKey("starGazers"))
        }
  }

  @Test
  fun clientControlledRemoveRecordsMemory() = clientControlledRemoveRecords(CacheManager(MemoryCacheFactory()))

  @Test
  fun clientControlledRemoveRecordsSql() = clientControlledRemoveRecords(CacheManager(SqlNormalizedCacheFactory()))

  @Test
  fun clientControlledRemoveRecordsChained() =
    clientControlledRemoveRecords(CacheManager(MemoryCacheFactory().chain(SqlNormalizedCacheFactory())))

  private fun clientControlledRemoveRecords(cacheManager: CacheManager) = runTest {
    val mockServer = MockServer()
    cacheManager.clearAll()
    ApolloClient.Builder()
        .serverUrl(mockServer.url())
        .cacheManager(cacheManager)
        .build()
        .use { apolloClient ->
          mockServer.enqueueString(PROJECT_LIST_RESPONSE)
          apolloClient.query(ProjectListQuery())
              .fetchPolicy(FetchPolicy.NetworkOnly)
              .cacheHeaders(receivedDate(currentTimeSeconds() - 60))
              .execute()

          var allRecords = cacheManager.accessCache { it.allRecords() }
          assertTrue(allRecords[CacheKey("projects").append("0")]!!.fields.containsKey("velocity"))
          assertTrue(allRecords[CacheKey("projects").append("0")]!!.fields.containsKey("isUrgent"))
          assertTrue(allRecords[CacheKey("projects").append("1")]!!.fields.containsKey("velocity"))
          assertTrue(allRecords[CacheKey("projects").append("1")]!!.fields.containsKey("isUrgent"))

          val maxAgeProvider = SchemaCoordinatesMaxAgeProvider(
              Cache.maxAges,
              defaultMaxAge = 120.seconds,
          )
          var removedFieldsAndRecords = apolloClient.apolloStore.removeStaleFields(maxAgeProvider)
          // Project.velocity has a max age of 60 seconds, so they should be removed / Project.isUrgent has a max age of 90 seconds, so they should be kept
          assertEquals(
              setOf(
                  CacheKey("projects").append("0").fieldKey("velocity"),
                  CacheKey("projects").append("1").fieldKey("velocity"),
              ), removedFieldsAndRecords.removedFields
          )
          assertEquals(
              emptySet(), removedFieldsAndRecords.removedRecords
          )
          allRecords = cacheManager.accessCache { it.allRecords() }
          assertFalse(allRecords[CacheKey("projects").append("0")]!!.fields.containsKey("velocity"))
          assertTrue(allRecords[CacheKey("projects").append("0")]!!.fields.containsKey("isUrgent"))
          assertFalse(allRecords[CacheKey("projects").append("1")]!!.fields.containsKey("velocity"))
          assertTrue(allRecords[CacheKey("projects").append("1")]!!.fields.containsKey("isUrgent"))

          mockServer.enqueueString(PROJECT_LIST_RESPONSE)
          apolloClient.query(ProjectListQuery())
              .fetchPolicy(FetchPolicy.NetworkOnly)
              .cacheHeaders(receivedDate(currentTimeSeconds() - 90))
              .execute()
          removedFieldsAndRecords = apolloClient.apolloStore.removeStaleFields(maxAgeProvider)
          // Project.velocity and Project.isUrgent should be removed, their records being empty they should be removed
          assertEquals(
              setOf(
                  CacheKey("projects").append("0").fieldKey("velocity"),
                  CacheKey("projects").append("0").fieldKey("isUrgent"),
                  CacheKey("projects").append("1").fieldKey("velocity"),
                  CacheKey("projects").append("1").fieldKey("isUrgent"),
              ), removedFieldsAndRecords.removedFields
          )
          assertEquals(
              setOf(
                  CacheKey("projects").append("0"),
                  CacheKey("projects").append("1"),
              ), removedFieldsAndRecords.removedRecords
          )
          allRecords = cacheManager.accessCache { it.allRecords() }
          assertFalse(allRecords.containsKey(CacheKey("projects").append("0")))
          assertFalse(allRecords.containsKey(CacheKey("projects").append("1")))
        }
  }

  @Test
  fun serverControlledRemoveFieldsMemory() = serverControlledRemoveFields(CacheManager(MemoryCacheFactory()))

  @Test
  fun serverControlledRemoveFieldsSql() = serverControlledRemoveFields(CacheManager(SqlNormalizedCacheFactory()))

  @Test
  fun serverControlledRemoveFieldsChained() =
    serverControlledRemoveFields(CacheManager(MemoryCacheFactory().chain(SqlNormalizedCacheFactory())))

  private fun serverControlledRemoveFields(cacheManager: CacheManager) = runTest {
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
              .cacheHeaders(expirationDate(currentTimeSeconds() - 60))
              .execute()

          var allRecords = cacheManager.accessCache { it.allRecords() }
          assertTrue(allRecords[CacheKey("Repository:0")]!!.fields.containsKey("stars"))
          assertTrue(allRecords[CacheKey("Repository:0")]!!.fields.containsKey("starGazers"))
          assertTrue(allRecords[CacheKey("Repository:1")]!!.fields.containsKey("stars"))
          assertTrue(allRecords[CacheKey("Repository:1")]!!.fields.containsKey("starGazers"))

          var removedFieldsAndRecords = apolloClient.apolloStore.removeStaleFields(GlobalMaxAgeProvider(Duration.INFINITE))
          // Everything is stale
          assertEquals(
              setOf(
                  CacheKey("Repository:0").fieldKey("__typename"),
                  CacheKey("Repository:0").fieldKey("id"),
                  CacheKey("Repository:0").fieldKey("stars"),
                  CacheKey("Repository:0").fieldKey("starGazers"),
                  CacheKey("User:0").fieldKey("__typename"),
                  CacheKey("User:0").fieldKey("id"),
                  CacheKey("User:0").fieldKey("name"),
                  CacheKey("Repository:1").fieldKey("__typename"),
                  CacheKey("Repository:1").fieldKey("id"),
                  CacheKey("Repository:1").fieldKey("stars"),
                  CacheKey("Repository:1").fieldKey("starGazers"),
                  CacheKey("User:2").fieldKey("__typename"),
                  CacheKey("User:2").fieldKey("id"),
                  CacheKey("User:2").fieldKey("name"),
                  CacheKey("QUERY_ROOT").fieldKey("repositories({\"first\":15})"),
                  CacheKey("User:1").fieldKey("__typename"),
                  CacheKey("User:1").fieldKey("id"),
                  CacheKey("User:1").fieldKey("name"),
              ), removedFieldsAndRecords.removedFields
          )
          assertEquals(
              setOf(
                  CacheKey("Repository:0"),
                  CacheKey("Repository:1"),
                  CacheKey("User:0"),
                  CacheKey("User:1"),
                  CacheKey("User:2"),
                  CacheKey("QUERY_ROOT"),
              ), removedFieldsAndRecords.removedRecords
          )
          allRecords = cacheManager.accessCache { it.allRecords() }
          assertTrue(allRecords.isEmpty())

          mockServer.enqueueString(REPOSITORY_LIST_RESPONSE)
          apolloClient.query(RepositoryListQuery())
              .fetchPolicy(FetchPolicy.NetworkOnly)
              .cacheHeaders(expirationDate(currentTimeSeconds() - 60))
              .execute()

          removedFieldsAndRecords =
            apolloClient.apolloStore.removeStaleFields(GlobalMaxAgeProvider(Duration.INFINITE), maxStale = 70.seconds)
          // Nothing is stale
          assertEquals(
              emptySet(),
              removedFieldsAndRecords.removedFields
          )
          assertEquals(
              emptySet(),
              removedFieldsAndRecords.removedRecords
          )
          allRecords = cacheManager.accessCache { it.allRecords() }
          assertTrue(allRecords[CacheKey("Repository:0")]!!.fields.containsKey("stars"))
          assertTrue(allRecords[CacheKey("Repository:0")]!!.fields.containsKey("starGazers"))
          assertTrue(allRecords[CacheKey("Repository:1")]!!.fields.containsKey("stars"))
          assertTrue(allRecords[CacheKey("Repository:1")]!!.fields.containsKey("starGazers"))
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
  private val PROJECT_LIST_RESPONSE = """
  {
    "data": {
      "projects": [
        {
          "__typename": "Project",
          "velocity": 12,
          "isUrgent": true
        },
        {
          "__typename": "Project",
          "velocity": 3,
          "isUrgent": false
        }
      ]
    }
  }
  """.trimIndent()
}

fun currentTimeSeconds() = currentTimeMillis() / 1000

fun receivedDate(receivedDateSeconds: Long): CacheHeaders {
  return CacheHeaders.Builder().addHeader(ApolloCacheHeaders.RECEIVED_DATE, receivedDateSeconds.toString()).build()
}

fun expirationDate(expirationDateSeconds: Long): CacheHeaders {
  return CacheHeaders.Builder().addHeader(ApolloCacheHeaders.EXPIRATION_DATE, expirationDateSeconds.toString()).build()
}
