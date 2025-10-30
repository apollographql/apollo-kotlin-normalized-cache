package test

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.exception.CacheMissException
import com.apollographql.apollo.mpp.currentTimeMillis
import com.apollographql.cache.normalized.CacheManager
import com.apollographql.cache.normalized.FetchPolicy
import com.apollographql.cache.normalized.api.CacheHeaders
import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.api.DefaultRecordMerger
import com.apollographql.cache.normalized.api.IdCacheKeyGenerator
import com.apollographql.cache.normalized.api.IdCacheResolver
import com.apollographql.cache.normalized.api.Record
import com.apollographql.cache.normalized.api.RecordValue
import com.apollographql.cache.normalized.cacheManager
import com.apollographql.cache.normalized.fetchPolicy
import com.apollographql.cache.normalized.memory.MemoryCacheFactory
import com.apollographql.cache.normalized.sql.SqlNormalizedCacheFactory
import com.apollographql.cache.normalized.testing.runTest
import com.apollographql.mockserver.MockServer
import com.apollographql.mockserver.enqueueString
import okio.use
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import com.apollographql.apollo.cache.normalized.ApolloStore as LegacyApolloStore
import com.apollographql.apollo.cache.normalized.api.CacheHeaders as LegacyCacheHeaders
import com.apollographql.apollo.cache.normalized.api.CacheKey as LegacyCacheKey
import com.apollographql.apollo.cache.normalized.api.MemoryCacheFactory as LegacyMemoryCacheFactory
import com.apollographql.apollo.cache.normalized.api.NormalizedCache as LegacyNormalizedCache
import com.apollographql.apollo.cache.normalized.api.Record as LegacyRecord
import com.apollographql.apollo.cache.normalized.api.RecordValue as LegacyRecordValue
import com.apollographql.apollo.cache.normalized.sql.SqlNormalizedCacheFactory as LegacySqlNormalizedCacheFactory
import com.apollographql.apollo.cache.normalized.store as legacyStore

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
        }
      ]
    }
  }
""".trimIndent()

private val REPOSITORY_LIST_DATA = RepositoryListQuery.Data(
    repositories = listOf(
        RepositoryListQuery.Repository(
            id = "0",
            stars = 10,
            starGazers = listOf(
                RepositoryListQuery.StarGazer(id = "0", name = "John", __typename = "User"),
                RepositoryListQuery.StarGazer(id = "1", name = "Jane", __typename = "User"),
            ),
            __typename = "Repository"
        )
    )
)

class MigrationTest {
  @Test
  fun canOpenLegacyDb() = runTest {
    val mockServer = MockServer()
    val name = "apollo-${currentTimeMillis()}.db"

    // Create a legacy store with some data
    val legacyStore = LegacyApolloStore(LegacyMemoryCacheFactory().chain(LegacySqlNormalizedCacheFactory(name = name)))
    ApolloClient.Builder()
        .serverUrl(mockServer.url())
        .legacyStore(legacyStore)
        .build()
        .use { apolloClient ->
          mockServer.enqueueString(REPOSITORY_LIST_RESPONSE)
          apolloClient.query(RepositoryListQuery())
              .fetchPolicy(FetchPolicy.NetworkOnly)
              .execute()
        }

    // Open the legacy store which empties it. Add/read some data to make sure it works.
    val cacheManager =
      CacheManager(MemoryCacheFactory().chain(SqlNormalizedCacheFactory(name = name)), cacheKeyGenerator = IdCacheKeyGenerator(), cacheResolver = IdCacheResolver())
    ApolloClient.Builder()
        .serverUrl(mockServer.url())
        .cacheManager(cacheManager)
        .build()
        .use { apolloClient ->
          // Expected cache miss: the db has been cleared
          var response = apolloClient.query(RepositoryListQuery())
              .fetchPolicy(FetchPolicy.CacheOnly)
              .execute()
          assertIs<CacheMissException>(response.exception)

          // Add some data
          mockServer.enqueueString(REPOSITORY_LIST_RESPONSE)
          apolloClient.query(RepositoryListQuery())
              .fetchPolicy(FetchPolicy.NetworkOnly)
              .execute()

          // Read the data back
          response = apolloClient.query(RepositoryListQuery())
              .fetchPolicy(FetchPolicy.CacheOnly)
              .execute()
          assertEquals(REPOSITORY_LIST_DATA, response.data)

          // Clean up
          cacheManager.clearAll()
        }
  }

  @Test
  fun migrateDb() = runTest {
    val mockServer = MockServer()
    // Create a legacy store with some data
    val legacyStore = LegacyApolloStore(LegacySqlNormalizedCacheFactory(name = "legacy.db")).also { it.clearAll() }
    ApolloClient.Builder()
        .serverUrl(mockServer.url())
        .legacyStore(legacyStore)
        .build()
        .use { apolloClient ->
          mockServer.enqueueString(REPOSITORY_LIST_RESPONSE)
          apolloClient.query(RepositoryListQuery())
              .fetchPolicy(FetchPolicy.NetworkOnly)
              .execute()
        }

    // Create a modern store and migrate the legacy data
    val cacheManager =
      CacheManager(SqlNormalizedCacheFactory(name = "modern.db"), cacheKeyGenerator = IdCacheKeyGenerator(), cacheResolver = IdCacheResolver()).also { it.clearAll() }
    cacheManager.migrateFrom(legacyStore)

    // Read the data back
    ApolloClient.Builder()
        .serverUrl(mockServer.url())
        .cacheManager(cacheManager)
        .build()
        .use { apolloClient ->
          val response = apolloClient.query(RepositoryListQuery())
              .fetchPolicy(FetchPolicy.CacheOnly)
              .execute()
          assertEquals(REPOSITORY_LIST_DATA, response.data)
        }
  }
}

private suspend fun CacheManager.migrateFrom(legacyStore: LegacyApolloStore) {
  accessCache { cache ->
    for (legacyRecords in legacyStore.accessCache { it.allRecordsSequence() }.chunked(50)) {
      cache.merge(
          records = legacyRecords.map { it.toRecord() },
          cacheHeaders = CacheHeaders.NONE,
          recordMerger = DefaultRecordMerger,
      )
    }
  }
}

private fun LegacyNormalizedCache.allRecordsSequence(): Sequence<LegacyRecord> {
  suspend fun SequenceScope<LegacyRecord>.yieldRecordsRecursively(cache: LegacyNormalizedCache, cacheKeys: List<LegacyCacheKey>) {
    for (cacheKeysChunk in cacheKeys.chunked(50)) {
      val records = cache.loadRecords(cacheKeysChunk.map{it.key}, LegacyCacheHeaders.NONE)
      yieldAll(records)
      val references = records.flatMap{it.references()}
      yieldRecordsRecursively(cache, references)
    }
  }
  return sequence {
    yieldRecordsRecursively(this@allRecordsSequence, listOf(LegacyCacheKey.rootKey()))
  }
}

private fun LegacyRecord.references(): List<LegacyCacheKey> {
  fun LegacyRecordValue.references(): List<LegacyCacheKey> {
    return when (this) {
      is LegacyCacheKey -> listOf(this)
      is List<*> -> this.flatMap { it.references() }
      is Map<*, *> -> this.values.flatMap { it.references() }
      else -> emptyList()
    }
  }
  return fields.values.flatMap { it.references() }
}

private fun LegacyRecord.toRecord(): Record = Record(
    key = CacheKey(key),
    fields = fields.mapValues { (_, value) -> value.toRecordValue() },
    mutationId = mutationId
)

private fun LegacyRecordValue.toRecordValue(): RecordValue = when (this) {
  is Map<*, *> -> mapValues { (_, value) -> value.toRecordValue() }
  is List<*> -> map { it.toRecordValue() }
  is LegacyCacheKey -> CacheKey(key)
  else -> this
}
