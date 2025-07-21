package test

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Error
import com.apollographql.cache.normalized.CacheManager
import com.apollographql.cache.normalized.DonkeyInterceptor
import com.apollographql.cache.normalized.allowCachedErrors
import com.apollographql.cache.normalized.allowCachedPartialResults
import com.apollographql.cache.normalized.cacheManager
import com.apollographql.cache.normalized.fetchPolicyInterceptor
import com.apollographql.cache.normalized.memory.MemoryCacheFactory
import com.apollographql.cache.normalized.noCache
import com.apollographql.cache.normalized.onlyIfCached
import com.apollographql.cache.normalized.sql.SqlNormalizedCacheFactory
import com.apollographql.cache.normalized.testing.assertErrorsEquals
import com.apollographql.cache.normalized.testing.runTest
import com.apollographql.mockserver.MockServer
import com.apollographql.mockserver.enqueueString
import okio.use
import kotlin.test.Test
import kotlin.test.assertEquals

class CachePolicyResponseMapperTest {
  private lateinit var mockServer: MockServer

  private suspend fun setUp() {
    mockServer = MockServer()
    sqlCacheManager.clearAll()
    memoryThenSqlCacheManager.clearAll()
  }

  private fun tearDown() {
    mockServer.close()
  }

  private val memoryCacheManager = CacheManager(MemoryCacheFactory())

  private val sqlCacheManager = CacheManager(SqlNormalizedCacheFactory())

  private val memoryThenSqlCacheManager = CacheManager(MemoryCacheFactory().chain(SqlNormalizedCacheFactory()))

  @Test
  fun simpleMemory() = runTest(before = { setUp() }, after = { tearDown() }) {
    simple(memoryCacheManager)
  }

  @Test
  fun simpleSql() = runTest(before = { setUp() }, after = { tearDown() }) {
    simple(sqlCacheManager)
  }

  @Test
  fun simpleMemoryThenSql() = runTest(before = { setUp() }, after = { tearDown() }) {
    simple(memoryThenSqlCacheManager)
  }

  private suspend fun simple(cacheManager: CacheManager) {
    mockServer.enqueueString(
        // language=JSON
        """
          {
            "data": {
              "me": {
                "__typename": "User",
                "id": "1",
                "firstName": "John",
                "lastName": "Smith",
                "nickName": null
              }
            },
            "errors": [
              {
                "message": "'nickName' can't be reached",
                "path": ["me", "nickName"]
              }
            ]
          }
          """
    )
    ApolloClient.Builder()
        .serverUrl(mockServer.url())
        .cacheManager(cacheManager)
        .fetchPolicyInterceptor(DonkeyInterceptor)
        .build()
        .use { apolloClient ->
          val networkResult = apolloClient.query(MeWithNickNameQuery())
              .noCache(true)
              .execute()
          assertEquals(
              MeWithNickNameQuery.Data(
                  MeWithNickNameQuery.Me(
                      __typename = "User",
                      id = "1",
                      firstName = "John",
                      lastName = "Smith",
                      nickName = null
                  )
              ),
              networkResult.data
          )
          assertErrorsEquals(
              listOf(
                  Error.Builder("'nickName' can't be reached").path(listOf("me", "nickName")).build()
              ),
              networkResult.errors
          )

          val cacheResult = apolloClient.query(MeWithNickNameQuery())
              .allowCachedErrors(true)
              .execute()
          assertEquals(
              networkResult.data,
              cacheResult.data
          )
          assertErrorsEquals(
              networkResult.errors,
              cacheResult.errors
          )
        }
  }

  @Test
  fun listsMemory() = runTest(before = { setUp() }, after = { tearDown() }) {
    lists(memoryCacheManager)
  }

  @Test
  fun listsSql() = runTest(before = { setUp() }, after = { tearDown() }) {
    lists(sqlCacheManager)
  }

  @Test
  fun listsMemoryThenSql() = runTest(before = { setUp() }, after = { tearDown() }) {
    lists(memoryThenSqlCacheManager)
  }

  private suspend fun lists(cacheManager: CacheManager) {
    mockServer.enqueueString(
        // language=JSON
        """
          {
            "data": {
              "users": [
                {
                  "__typename": "User",
                  "id": "1",
                  "firstName": "John",
                  "lastName": "Smith",
                  "email": "jsmith@example.com"
                },
                {
                  "__typename": "User",
                  "id": "2",
                  "firstName": "Jane",
                  "lastName": "Doe",
                  "email": "jdoe@example.com"
                },
                null
              ]
            },
            "errors": [
              {
                "message": "User `3` not found",
                "path": ["users", 2]
              }
            ]
          }
          """
    )
    ApolloClient.Builder()
        .serverUrl(mockServer.url())
        .cacheManager(cacheManager)
        .fetchPolicyInterceptor(DonkeyInterceptor)
        .build()
        .use { apolloClient ->
          val networkResult = apolloClient.query(UsersQuery(listOf("1", "2", "3")))
              .noCache(true)
              .execute()
          assertEquals(
              UsersQuery.Data(
                  users = listOf(
                      UsersQuery.User(
                          __typename = "User",
                          id = "1",
                          firstName = "John",
                          lastName = "Smith",
                          email = "jsmith@example.com",
                      ),
                      UsersQuery.User(
                          __typename = "User",
                          id = "2",
                          firstName = "Jane",
                          lastName = "Doe",
                          email = "jdoe@example.com",
                      ),
                      null,
                  )
              ),
              networkResult.data
          )
          assertErrorsEquals(
              listOf(
                  Error.Builder("User `3` not found").path(listOf("users", 2)).build()
              ),
              networkResult.errors
          )

          val cacheResult = apolloClient.query(UsersQuery(listOf("1", "2", "3")))
              .onlyIfCached(true)
              .allowCachedErrors(true)
              .execute()
          assertEquals(
              networkResult.data,
              cacheResult.data,
          )
          assertErrorsEquals(
              networkResult.errors,
              cacheResult.errors,
          )
        }
  }

  @Test
  fun cacheMissAndErrorsMemory() = runTest(before = { setUp() }, after = { tearDown() }) {
    cacheMissAndErrors(memoryCacheManager)
  }

  @Test
  fun cacheMissAndErrorsSql() = runTest(before = { setUp() }, after = { tearDown() }) {
    cacheMissAndErrors(sqlCacheManager)
  }

  @Test
  fun cacheMissAndErrorsMemoryThenSql() = runTest(before = { setUp() }, after = { tearDown() }) {
    cacheMissAndErrors(memoryThenSqlCacheManager)
  }

  private suspend fun cacheMissAndErrors(cacheManager: CacheManager) {
    mockServer.enqueueString(
        // language=JSON
        """
          {
            "data": {
              "me": {
                "__typename": "User",
                "id": "1",
                "firstName": "John",
                "lastName": "Smith",
                "nickName": null
              }
            },
            "errors": [
              {
                "message": "'nickName' can't be reached",
                "path": ["me", "nickName"]
              }
            ]
          }
          """
    )
    ApolloClient.Builder()
        .serverUrl(mockServer.url())
        .cacheManager(cacheManager)
        .fetchPolicyInterceptor(DonkeyInterceptor)
        .build()
        .use { apolloClient ->
          val networkResult = apolloClient.query(MeWithNickNameQuery())
              .noCache(true)
              .execute()
          assertEquals(
              MeWithNickNameQuery.Data(
                  MeWithNickNameQuery.Me(
                      __typename = "User",
                      id = "1",
                      firstName = "John",
                      lastName = "Smith",
                      nickName = null
                  )
              ),
              networkResult.data
          )
          assertErrorsEquals(
              listOf(
                  Error.Builder("'nickName' can't be reached").path(listOf("me", "nickName")).build()
              ),
              networkResult.errors
          )

          val cacheResult = apolloClient.query(MeWithNickNameAndProjectQuery())
              .onlyIfCached(true)
              .allowCachedPartialResults(true)
              .allowCachedErrors(true)
              .execute()
          assertEquals(
              MeWithNickNameAndProjectQuery.Data(
                  MeWithNickNameAndProjectQuery.Me(
                      __typename = "User",
                      id = "1",
                      firstName = "John",
                      lastName = "Smith",
                      nickName = null,
                      bestFriend = null
                  )
              ),
              cacheResult.data
          )
          assertErrorsEquals(
              networkResult.errors!! + listOf(
                  Error.Builder("Object 'User:1' has no field named 'bestFriend' in the cache").path(listOf("me", "bestFriend")).build()
              ),
              cacheResult.errors
          )
        }
  }
}
