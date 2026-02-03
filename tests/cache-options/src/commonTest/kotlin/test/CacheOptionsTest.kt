package test

import com.apollographql.apollo.ApolloCall
import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.ApolloResponse
import com.apollographql.apollo.api.Error
import com.apollographql.apollo.api.Operation
import com.apollographql.apollo.exception.CacheMissException
import com.apollographql.cache.normalized.CacheManager
import com.apollographql.cache.normalized.FetchPolicy
import com.apollographql.cache.normalized.api.FieldPolicyCacheResolver
import com.apollographql.cache.normalized.api.TypePolicyCacheKeyGenerator
import com.apollographql.cache.normalized.cacheManager
import com.apollographql.cache.normalized.fetchPolicy
import com.apollographql.cache.normalized.memory.MemoryCacheFactory
import com.apollographql.cache.normalized.options.cacheMissesAsException
import com.apollographql.cache.normalized.options.serverErrorsAsException
import com.apollographql.cache.normalized.testing.SqlNormalizedCacheFactory
import com.apollographql.cache.normalized.testing.assertErrorsEquals
import com.apollographql.cache.normalized.testing.runTest
import com.apollographql.mockserver.MockServer
import com.apollographql.mockserver.enqueueString
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.toList
import okio.use
import test.cache.Cache
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class CacheOptionsTest {
  private lateinit var mockServer: MockServer

  private suspend fun setUp() {
    mockServer = MockServer()
    sqlCacheManager.clearAll()
    memoryThenSqlCacheManager.clearAll()
  }

  private fun tearDown() {
    mockServer.close()
  }

  private val memoryCacheManager =
    CacheManager(
        normalizedCacheFactory = MemoryCacheFactory(),
        cacheKeyGenerator = TypePolicyCacheKeyGenerator(Cache.typePolicies),
        cacheResolver = FieldPolicyCacheResolver(Cache.fieldPolicies),
    )

  private val sqlCacheManager =
    CacheManager(
        normalizedCacheFactory = SqlNormalizedCacheFactory(),
        cacheKeyGenerator = TypePolicyCacheKeyGenerator(Cache.typePolicies),
        cacheResolver = FieldPolicyCacheResolver(Cache.fieldPolicies),
    )

  private val memoryThenSqlCacheManager =
    CacheManager(
        normalizedCacheFactory = MemoryCacheFactory().chain(SqlNormalizedCacheFactory()),
        cacheKeyGenerator = TypePolicyCacheKeyGenerator(Cache.typePolicies),
        cacheResolver = FieldPolicyCacheResolver(Cache.fieldPolicies),
    )

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
          """,
    )
    ApolloClient.Builder()
        .serverUrl(mockServer.url())
        .cacheManager(cacheManager)
        .build()
        .use { apolloClient ->
          val networkResult = apolloClient.query(MeWithNickNameQuery())
              .fetchPolicy(FetchPolicy.NetworkOnly)
              .execute()
          assertEquals(
              MeWithNickNameQuery.Data(
                  MeWithNickNameQuery.Me(
                      __typename = "User",
                      id = "1",
                      firstName = "John",
                      lastName = "Smith",
                      nickName = null,
                  ),
              ),
              networkResult.data,
          )
          assertErrorsEquals(
              listOf(
                  Error.Builder("'nickName' can't be reached").path(listOf("me", "nickName")).build(),
              ),
              networkResult.errors,
          )

          val cacheResult = apolloClient.query(MeWithNickNameQuery())
              .serverErrorsAsException(false)
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
  fun simpleWithIdMemory() = runTest(before = { setUp() }, after = { tearDown() }) {
    simpleWithId(memoryCacheManager)
  }

  @Test
  fun simpleWithIdSql() = runTest(before = { setUp() }, after = { tearDown() }) {
    simpleWithId(sqlCacheManager)
  }

  @Test
  fun simpleWithIdMemoryThenSql() = runTest(before = { setUp() }, after = { tearDown() }) {
    simpleWithId(memoryThenSqlCacheManager)
  }

  private suspend fun simpleWithId(cacheManager: CacheManager) {
    mockServer.enqueueString(
        // language=JSON
        """
          {
            "data": {
              "user": {
                "__typename": "User",
                "id": "1",
                "firstName": "John",
                "lastName": "Smith",
                "email": "jdoe@example.com"
              }
            }
          }
          """,
    )
    mockServer.enqueueString(
        // language=JSON
        """
          {
            "data": {
              "user": null
            },
            "errors": [
              {
                "message": "'User' can't be reached",
                "path": ["user"]
              }
            ]
          }
          """,
    )
    ApolloClient.Builder()
        .serverUrl(mockServer.url())
        .cacheManager(cacheManager)
        .build()
        .use { apolloClient ->
          val networkResult1 = apolloClient.query(UserByCategoryQuery(Category(0, "test")))
              .fetchPolicy(FetchPolicy.NetworkOnly)
              .execute()
          assertEquals(
              UserByCategoryQuery.Data(
                  user = UserByCategoryQuery.User(
                      __typename = "User",
                      id = "1",
                      firstName = "John",
                      lastName = "Smith",
                      email = "jdoe@example.com",
                  ),
              ),
              networkResult1.data,
          )
          assertNull(networkResult1.errors)

          val networkResult2 = apolloClient.query(UserByCategoryQuery(Category(1, "test2")))
              .fetchPolicy(FetchPolicy.NetworkOnly)
              .execute()
          assertEquals(
              UserByCategoryQuery.Data(
                  user = null,
              ),
              networkResult2.data,
          )
          assertErrorsEquals(
              listOf(
                  Error.Builder("'User' can't be reached").path(listOf("user")).build(),
              ),
              networkResult2.errors,
          )

          val cacheResult = apolloClient.query(UserByCategoryQuery(Category(1, "test2")))
              .fetchPolicy(FetchPolicy.CacheOnly)
              .serverErrorsAsException(false)
              .cacheMissesAsException(false)
              .execute()
          assertEquals(
              networkResult2.data,
              cacheResult.data,
          )
          assertErrorsEquals(
              networkResult2.errors,
              cacheResult.errors,
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
          """,
    )
    mockServer.enqueueString(
        // language=JSON
        """
          {
            "data": {
              "users": [
                {
                  "__typename": "User",
                  "id": "4",
                  "firstName": "Kevin",
                  "lastName": "Jones",
                  "email": "kjones@example.com"
                },
                {
                  "__typename": "User",
                  "id": "5",
                  "firstName": "Alice",
                  "lastName": "Johnson",
                  "email": "ajohnson@example.com"
                },
                null
              ]
            },
            "errors": [
              {
                "message": "User `6` not found",
                "path": ["users", 2]
              }
            ]
          }
          """,
    )

    ApolloClient.Builder()
        .serverUrl(mockServer.url())
        .cacheManager(cacheManager)
        .build()
        .use { apolloClient ->
          val networkResult1 = apolloClient.query(UsersQuery(listOf("1", "2", "3")))
              .fetchPolicy(FetchPolicy.NetworkOnly)
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
                  ),
              ),
              networkResult1.data,
          )
          assertErrorsEquals(
              listOf(
                  Error.Builder("User `3` not found").path(listOf("users", 2)).build(),
              ),
              networkResult1.errors,
          )

          val networkResult2 = apolloClient.query(UsersQuery(listOf("4", "5", "6")))
              .fetchPolicy(FetchPolicy.NetworkOnly)
              .execute()
          assertEquals(
              UsersQuery.Data(
                  users = listOf(
                      UsersQuery.User(
                          __typename = "User",
                          id = "4",
                          firstName = "Kevin",
                          lastName = "Jones",
                          email = "kjones@example.com",
                      ),
                      UsersQuery.User(
                          __typename = "User",
                          id = "5",
                          firstName = "Alice",
                          lastName = "Johnson",
                          email = "ajohnson@example.com",
                      ),
                      null,
                  ),
              ),
              networkResult2.data,
          )
          assertErrorsEquals(
              listOf(
                  Error.Builder("User `6` not found").path(listOf("users", 2)).build(),
              ),
              networkResult2.errors,
          )


          val cacheResult = apolloClient.query(UsersQuery(listOf("1", "2", "3", "4", "5", "6", "7")))
              .fetchPolicy(FetchPolicy.CacheOnly)
              .serverErrorsAsException(false)
              .cacheMissesAsException(false)
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
                      UsersQuery.User(
                          __typename = "User",
                          id = "4",
                          firstName = "Kevin",
                          lastName = "Jones",
                          email = "kjones@example.com",
                      ),
                      UsersQuery.User(
                          __typename = "User",
                          id = "5",
                          firstName = "Alice",
                          lastName = "Johnson",
                          email = "ajohnson@example.com",
                      ),
                      null,
                      null,
                  ),
              ),
              cacheResult.data,
          )
          assertErrorsEquals(
              listOf(
                  Error.Builder("User `3` not found").path(listOf("users", 2)).build(),
                  Error.Builder("User `6` not found").path(listOf("users", 5)).build(),
                  Error.Builder("Object 'User:7' not found in the cache").path(listOf("users", 6)).build(),
              ),
              cacheResult.errors,
          )
        }
  }

  @Test
  fun listsWithNullMemory() = runTest(before = { setUp() }, after = { tearDown() }) {
    listsWithNull(memoryCacheManager)
  }

  @Test
  fun listsWithNullSql() = runTest(before = { setUp() }, after = { tearDown() }) {
    listsWithNull(sqlCacheManager)
  }

  @Test
  fun listsWithNullMemoryThenSql() = runTest(before = { setUp() }, after = { tearDown() }) {
    listsWithNull(memoryThenSqlCacheManager)
  }

  private suspend fun listsWithNull(cacheManager: CacheManager) {
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
            }
          }
          """,
    )
    mockServer.enqueueString(
        // language=JSON
        """
          {
            "data": {
              "users": [
                {
                  "__typename": "User",
                  "id": "4",
                  "firstName": "Kevin",
                  "lastName": "Jones",
                  "email": "kjones@example.com"
                },
                {
                  "__typename": "User",
                  "id": "5",
                  "firstName": "Alice",
                  "lastName": "Johnson",
                  "email": "ajohnson@example.com"
                },
                null
              ]
            }
          }
          """,
    )

    ApolloClient.Builder()
        .serverUrl(mockServer.url())
        .cacheManager(cacheManager)
        .build()
        .use { apolloClient ->
          val networkResult1 = apolloClient.query(UsersQuery(listOf("1", "2", "3")))
              .fetchPolicy(FetchPolicy.NetworkOnly)
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
                  ),
              ),
              networkResult1.data,
          )
          assertNull(networkResult1.errors)

          val networkResult2 = apolloClient.query(UsersQuery(listOf("4", "5", "6")))
              .fetchPolicy(FetchPolicy.NetworkOnly)
              .execute()
          assertEquals(
              UsersQuery.Data(
                  users = listOf(
                      UsersQuery.User(
                          __typename = "User",
                          id = "4",
                          firstName = "Kevin",
                          lastName = "Jones",
                          email = "kjones@example.com",
                      ),
                      UsersQuery.User(
                          __typename = "User",
                          id = "5",
                          firstName = "Alice",
                          lastName = "Johnson",
                          email = "ajohnson@example.com",
                      ),
                      null,
                  ),
              ),
              networkResult2.data,
          )
          assertNull(networkResult2.errors)


          val cacheResult = apolloClient.query(UsersQuery(listOf("1", "2", "3", "4", "5", "6")))
              .fetchPolicy(FetchPolicy.CacheOnly)
              .serverErrorsAsException(false)
              .cacheMissesAsException(false)
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
                      UsersQuery.User(
                          __typename = "User",
                          id = "4",
                          firstName = "Kevin",
                          lastName = "Jones",
                          email = "kjones@example.com",
                      ),
                      UsersQuery.User(
                          __typename = "User",
                          id = "5",
                          firstName = "Alice",
                          lastName = "Johnson",
                          email = "ajohnson@example.com",
                      ),
                      null,
                  ),
              ),
              cacheResult.data,
          )
          assertNull(cacheResult.errors)
        }
  }


  @Test
  fun listsFromDifferentFieldsMemory() = runTest(before = { setUp() }, after = { tearDown() }) {
    listsFromDifferentFields(memoryCacheManager)
  }

  @Test
  fun listsFromDifferentFieldsSql() = runTest(before = { setUp() }, after = { tearDown() }) {
    listsFromDifferentFields(sqlCacheManager)
  }

  @Test
  fun listsFromDifferentFieldsMemoryThenSql() = runTest(before = { setUp() }, after = { tearDown() }) {
    listsFromDifferentFields(memoryThenSqlCacheManager)
  }

  private suspend fun listsFromDifferentFields(cacheManager: CacheManager) {
    mockServer.enqueueString(
        // language=JSON
        """
          {
            "data": {
              "allUsers": [
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
                "path": ["allUsers", 2]
              }
            ]
          }
          """,
    )

    ApolloClient.Builder()
        .serverUrl(mockServer.url())
        .cacheManager(cacheManager)
        .build()
        .use { apolloClient ->
          val networkResult1 = apolloClient.query(AllUsersQuery())
              .fetchPolicy(FetchPolicy.NetworkOnly)
              .execute()
          assertEquals(
              AllUsersQuery.Data(
                  allUsers = listOf(
                      AllUsersQuery.AllUser(
                          __typename = "User",
                          id = "1",
                          firstName = "John",
                          lastName = "Smith",
                          email = "jsmith@example.com",
                      ),
                      AllUsersQuery.AllUser(
                          __typename = "User",
                          id = "2",
                          firstName = "Jane",
                          lastName = "Doe",
                          email = "jdoe@example.com",
                      ),
                      null,
                  ),
              ),
              networkResult1.data,
          )
          assertErrorsEquals(
              listOf(
                  Error.Builder("User `3` not found").path(listOf("allUsers", 2)).build(),
              ),
              networkResult1.errors,
          )

          val cacheResult1 = apolloClient.query(AllUsersQuery())
              .fetchPolicy(FetchPolicy.CacheOnly)
              .serverErrorsAsException(false)
              .cacheMissesAsException(false)
              .execute()
          assertEquals(
              AllUsersQuery.Data(
                  allUsers = listOf(
                      AllUsersQuery.AllUser(
                          __typename = "User",
                          id = "1",
                          firstName = "John",
                          lastName = "Smith",
                          email = "jsmith@example.com",
                      ),
                      AllUsersQuery.AllUser(
                          __typename = "User",
                          id = "2",
                          firstName = "Jane",
                          lastName = "Doe",
                          email = "jdoe@example.com",
                      ),
                      null,
                  ),
              ),
              cacheResult1.data,
          )
          assertErrorsEquals(
              listOf(
                  Error.Builder("User `3` not found").path(listOf("allUsers", 2)).build(),
              ),
              cacheResult1.errors,
          )

          val cacheResult2 = apolloClient.query(UsersQuery(listOf("1", "2", "3", "4")))
              .fetchPolicy(FetchPolicy.CacheOnly)
              .serverErrorsAsException(false)
              .cacheMissesAsException(false)
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
                      null,
                  ),
              ),
              cacheResult2.data,
          )
          assertErrorsEquals(
              listOf(
                  // From AllUsersQuery, we could expect to have the same error "User `3` not found" being returned here for User:3.
                  // But this error is not stored at `User:3` but inside the list at `allUsers`. There is no way for the cache to know
                  // how to store it at `User:3`, since the id is not available in the response.
                  // So instead it results in a cache miss.
                  Error.Builder("Object 'User:3' not found in the cache").path(listOf("users", 2)).build(),
                  Error.Builder("Object 'User:4' not found in the cache").path(listOf("users", 3)).build(),
              ),
              cacheResult2.errors,
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
          """,
    )
    ApolloClient.Builder()
        .serverUrl(mockServer.url())
        .cacheManager(cacheManager)
        .cacheMissesAsException(false)
        .serverErrorsAsException(false)
        .build()
        .use { apolloClient ->
          val networkResult = apolloClient.query(MeWithNickNameQuery())
              .fetchPolicy(FetchPolicy.NetworkOnly)
              .execute()
          assertEquals(
              MeWithNickNameQuery.Data(
                  MeWithNickNameQuery.Me(
                      __typename = "User",
                      id = "1",
                      firstName = "John",
                      lastName = "Smith",
                      nickName = null,
                  ),
              ),
              networkResult.data,
          )
          assertErrorsEquals(
              listOf(
                  Error.Builder("'nickName' can't be reached").path(listOf("me", "nickName")).build(),
              ),
              networkResult.errors,
          )

          val cacheResult = apolloClient.query(MeWithNickNameAndProjectQuery())
              .fetchPolicy(FetchPolicy.CacheOnly)
              .execute()
          assertEquals(
              MeWithNickNameAndProjectQuery.Data(
                  MeWithNickNameAndProjectQuery.Me(
                      __typename = "User",
                      id = "1",
                      firstName = "John",
                      lastName = "Smith",
                      nickName = null,
                      bestFriend = null,
                  ),
              ),
              cacheResult.data,
          )
          assertErrorsEquals(
              networkResult.errors!! + listOf(
                  Error.Builder("Object 'User:1' has no field named 'bestFriend' in the cache").path(listOf("me", "bestFriend")).build(),
              ),
              cacheResult.errors,
          )
        }
  }

  @Test
  fun cacheAndNetworkMemory() = runTest(before = { setUp() }, after = { tearDown() }) {
    cacheAndNetwork(memoryCacheManager)
  }

  @Test
  fun cacheAndNetworkSql() = runTest(before = { setUp() }, after = { tearDown() }) {
    cacheAndNetwork(sqlCacheManager)
  }

  @Test
  fun cacheAndNetworkMemoryThenSql() = runTest(before = { setUp() }, after = { tearDown() }) {
    cacheAndNetwork(memoryThenSqlCacheManager)
  }

  private suspend fun cacheAndNetwork(cacheManager: CacheManager) {
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
                "nickName": "js"
              }
            }
          }
          """,
    )
    ApolloClient.Builder()
        .serverUrl(mockServer.url())
        .cacheManager(cacheManager)
        .build()
        .use { apolloClient ->
          val results = apolloClient.query(MeWithNickNameQuery())
              .executeCacheAndNetwork()
              .toList()

          assertNull(results[0].data)
          assertIs<CacheMissException>(results[0].exception)

          assertEquals(
              MeWithNickNameQuery.Data(
                  MeWithNickNameQuery.Me(
                      __typename = "User",
                      id = "1",
                      firstName = "John",
                      lastName = "Smith",
                      nickName = "js",
                  ),
              ),
              results[1].data,
          )
          assertNull(results[1].exception)
        }
  }
}

private fun <D : Operation.Data> ApolloCall<D>.executeCacheAndNetwork(): Flow<ApolloResponse<D>> {
  return fetchPolicy(FetchPolicy.CacheOnly).toFlow().onCompletion {
    emitAll(fetchPolicy(FetchPolicy.NetworkOnly).toFlow())
  }
}
