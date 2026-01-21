package test

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.ApolloRequest
import com.apollographql.apollo.api.ApolloResponse
import com.apollographql.apollo.api.Error
import com.apollographql.apollo.api.Operation
import com.apollographql.apollo.interceptor.ApolloInterceptor
import com.apollographql.apollo.interceptor.ApolloInterceptorChain
import com.apollographql.cache.normalized.CacheManager
import com.apollographql.cache.normalized.FetchPolicy
import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.api.FieldPolicyCacheResolver
import com.apollographql.cache.normalized.api.Record
import com.apollographql.cache.normalized.api.TypePolicyCacheKeyGenerator
import com.apollographql.cache.normalized.api.withErrors
import com.apollographql.cache.normalized.cacheManager
import com.apollographql.cache.normalized.errorsReplaceCachedValues
import com.apollographql.cache.normalized.fetchFromCache
import com.apollographql.cache.normalized.fetchPolicy
import com.apollographql.cache.normalized.fetchPolicyInterceptor
import com.apollographql.cache.normalized.memory.MemoryCacheFactory
import com.apollographql.cache.normalized.options.serverErrorsAsCacheMisses
import com.apollographql.cache.normalized.options.throwOnCacheMiss
import com.apollographql.cache.normalized.testing.SqlNormalizedCacheFactory
import com.apollographql.cache.normalized.testing.assertErrorsEquals
import com.apollographql.cache.normalized.testing.runTest
import com.apollographql.mockserver.MockServer
import com.apollographql.mockserver.enqueueString
import kotlinx.coroutines.flow.Flow
import okio.use
import test.cache.Cache
import test.fragment.UserFields
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StoreErrorsTest {
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
    CacheManager(MemoryCacheFactory(), cacheKeyGenerator = TypePolicyCacheKeyGenerator(Cache.typePolicies), cacheResolver = FieldPolicyCacheResolver(Cache.fieldPolicies))

  private val sqlCacheManager =
    CacheManager(SqlNormalizedCacheFactory(), cacheKeyGenerator = TypePolicyCacheKeyGenerator(Cache.typePolicies), cacheResolver = FieldPolicyCacheResolver(Cache.fieldPolicies))

  private val memoryThenSqlCacheManager =
    CacheManager(MemoryCacheFactory().chain(SqlNormalizedCacheFactory()), cacheKeyGenerator = TypePolicyCacheKeyGenerator(Cache.typePolicies), cacheResolver = FieldPolicyCacheResolver(Cache.fieldPolicies))

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
              .fetchPolicyInterceptor(PartialCacheOnlyInterceptor)
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
        .build()
        .use { apolloClient ->
          val networkResult = apolloClient.query(UsersQuery(listOf("1", "2", "3")))
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
              .fetchPolicyInterceptor(PartialCacheOnlyInterceptor)
              .execute()
          assertEquals(
              networkResult.data,
              cacheResult.data,
          )
          assertErrorsEquals(
              networkResult.errors,
              cacheResult.errors
          )
        }
  }

  @Test
  fun compositeMemory() = runTest(before = { setUp() }, after = { tearDown() }) {
    composite(memoryCacheManager)
  }

  @Test
  fun compositeSql() = runTest(before = { setUp() }, after = { tearDown() }) {
    composite(sqlCacheManager)
  }

  @Test
  fun compositeMemoryThenSql() = runTest(before = { setUp() }, after = { tearDown() }) {
    composite(memoryThenSqlCacheManager)
  }

  private suspend fun composite(cacheManager: CacheManager) {
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
                "bestFriend": null,
                "projects": [
                  {
                    "__typename": "Project",
                    "lead": {
                      "__typename": "User",
                      "id": "3",
                      "firstName": "Amanda",
                      "lastName": "Brown"
                    },
                    "users": [
                      {
                        "__typename": "User",
                        "id": "4",
                        "firstName": "Alice",
                        "lastName": "White"
                      }
                    ]
                  }
                ]
              }
            },
            "errors": [
              {
                "message": "Cannot find best friend",
                "path": ["me", "bestFriend"]
              }
            ]
          }
          """
    )
    ApolloClient.Builder()
        .serverUrl(mockServer.url())
        .cacheManager(cacheManager)
        .build()
        .use { apolloClient ->
          val networkResult = apolloClient.query(MeWithBestFriendQuery())
              .fetchPolicy(FetchPolicy.NetworkOnly)
              .execute()
          assertEquals(
              MeWithBestFriendQuery.Data(
                  MeWithBestFriendQuery.Me(
                      __typename = "User",
                      id = "1",
                      firstName = "John",
                      lastName = "Smith",
                      bestFriend = null,
                      projects = listOf(
                          MeWithBestFriendQuery.Project(
                              __typename = "Project",
                              lead = MeWithBestFriendQuery.Lead(
                                  __typename = "User",
                                  id = "3",
                                  firstName = "Amanda",
                                  lastName = "Brown"
                              ),
                              users = listOf(
                                  MeWithBestFriendQuery.User(
                                      __typename = "User",
                                      id = "4",
                                      firstName = "Alice",
                                      lastName = "White"
                                  )
                              )
                          )
                      )
                  )
              ),
              networkResult.data
          )
          assertErrorsEquals(
              listOf(
                  Error.Builder("Cannot find best friend").path(listOf("me", "bestFriend")).build()
              ),
              networkResult.errors
          )
          val cacheResult = apolloClient.query(MeWithBestFriendQuery())
              .fetchPolicyInterceptor(PartialCacheOnlyInterceptor)
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
  fun aliasesMemory() = runTest(before = { setUp() }, after = { tearDown() }) {
    aliases(memoryCacheManager)
  }

  @Test
  fun aliasesSql() = runTest(before = { setUp() }, after = { tearDown() }) {
    aliases(sqlCacheManager)
  }

  @Test
  fun aliasesMemoryThenSql() = runTest(before = { setUp() }, after = { tearDown() }) {
    aliases(memoryThenSqlCacheManager)
  }

  private suspend fun aliases(cacheManager: CacheManager) {
    mockServer.enqueueString(
        // language=JSON
        """
          {
            "data": {
              "project": null,
              "project2": {
                "__typename": "Project",
                "id": "44",
                "name": "Atlantis",
                "description": "The lost city of water"
              }
            },
            "errors": [
              {
                "message": "Project `42` not found",
                "path": ["project"]
              }
            ]
          }
          """
    )
    ApolloClient.Builder()
        .serverUrl(mockServer.url())
        .cacheManager(cacheManager)
        .build()
        .use { apolloClient ->
          val networkResult = apolloClient.query(DefaultProjectQuery())
              .fetchPolicy(FetchPolicy.NetworkOnly)
              .execute()
          assertEquals(
              DefaultProjectQuery.Data(
                  project = null,
                  project2 = DefaultProjectQuery.Project2(
                      __typename = "Project",
                      id = "44",
                      name = "Atlantis",
                      description = "The lost city of water"
                  )
              ),
              networkResult.data
          )
          assertErrorsEquals(
              listOf(
                  Error.Builder("Project `42` not found").path(listOf("project")).build()
              ),
              networkResult.errors
          )

          val cacheResult = apolloClient.query(DefaultProjectQuery())
              .fetchPolicyInterceptor(PartialCacheOnlyInterceptor)
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
  fun fragmentsAndAliasesMemory() = runTest(before = { setUp() }, after = { tearDown() }) {
    fragmentsAndAliases(memoryCacheManager)
  }

  @Test
  fun fragmentsAndAliasesSql() = runTest(before = { setUp() }, after = { tearDown() }) {
    fragmentsAndAliases(sqlCacheManager)
  }

  @Test
  fun fragmentsAndAliasesMemoryThenSql() = runTest(before = { setUp() }, after = { tearDown() }) {
    fragmentsAndAliases(memoryThenSqlCacheManager)
  }

  private fun fragmentsAndAliases(cacheManager: CacheManager) = runTest(before = { setUp() }, after = { tearDown() }) {
    mockServer.enqueueString(
        // language=JSON
        """
        {
          "data": {
            "me": {
              "__typename": "User",
              "id": "1",
              "firstName0": "John",
              "mainProject": {
                "__typename": "Project",
                "id": "1",
                "lead0": {
                  "id": "2",
                  "__typename": "User",
                  "firstName": "Jane"
                }
              },
              "lastName": "Smith",
              "nickName0": "JS",
              "email0": "jdoe@example.com",
              "category": {
                "code": 1,
                "name": "First"
              },
              "bestFriend0": null
            }
          },
          "errors": [
            {
              "message": "Cannot find best friend",
              "path": ["me", "bestFriend0"]
            }
          ]
        }
        """
    )
    ApolloClient.Builder()
        .serverUrl(mockServer.url())
        .cacheManager(cacheManager)
        .build()
        .use { apolloClient ->
          val networkResult = apolloClient.query(WithFragmentsQuery())
              .fetchPolicy(FetchPolicy.NetworkOnly)
              .execute()
          assertEquals(
              WithFragmentsQuery.Data(
                  WithFragmentsQuery.Me(
                      __typename = "User",
                      id = "1",
                      firstName0 = "John",
                      mainProject = WithFragmentsQuery.MainProject(
                          __typename = "Project",
                          id = "1",
                          lead0 = WithFragmentsQuery.Lead0(
                              id = "2",
                              __typename = "User",
                              firstName = "Jane",
                          ),
                      ),
                      onUser = WithFragmentsQuery.OnUser(
                          __typename = "User",
                          lastName = "Smith",
                          onUser = WithFragmentsQuery.OnUser1(
                              nickName0 = "JS"
                          ),
                      ),
                      userFields = UserFields(
                          email0 = "jdoe@example.com",
                          category = Category(
                              code = 1,
                              name = "First"
                          ),
                          id = "1",
                          __typename = "User",
                          bestFriend0 = null
                      ),
                  )
              ),
              networkResult.data
          )

          assertErrorsEquals(
              listOf(
                  Error.Builder("Cannot find best friend").path(listOf("me", "bestFriend0")).build()
              ),
              networkResult.errors
          )

          val cacheResult = apolloClient.query(WithFragmentsQuery())
              .fetchPolicyInterceptor(PartialCacheOnlyInterceptor)
              .execute()
          assertEquals(
              networkResult.data,
              cacheResult.data
          )
        }
  }


  @Test
  fun errorsReplaceCachedValuesMemory() = runTest(before = { setUp() }, after = { tearDown() }) {
    errorsReplaceCachedValues(memoryCacheManager)
  }

  @Test
  fun errorsReplaceCachedValuesSql() = runTest(before = { setUp() }, after = { tearDown() }) {
    errorsReplaceCachedValues(sqlCacheManager)
  }

  @Test
  fun errorsReplaceCachedValuesMemoryThenSql() = runTest(before = { setUp() }, after = { tearDown() }) {
    errorsReplaceCachedValues(memoryThenSqlCacheManager)
  }

  private suspend fun errorsReplaceCachedValues(cacheManager: CacheManager) {
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
                "nickName": "JS"
              }
            }
          }
          """
    )
    mockServer.enqueueString(
        // language=JSON
        """
          {
            "data": {
              "me": {
                "__typename": "User",
                "id": "1",
                "firstName": "Johnny",
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
    mockServer.enqueueString(
        // language=JSON
        """
          {
            "data": {
              "me": {
                "__typename": "User",
                "id": "1",
                "firstName": "Johnny Boy",
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
        .build()
        .use { apolloClient ->
          val noErrorNetworkResult = apolloClient.query(MeWithNickNameQuery())
              .fetchPolicy(FetchPolicy.NetworkOnly)
              .execute()
          assertEquals(
              MeWithNickNameQuery.Data(
                  MeWithNickNameQuery.Me(
                      __typename = "User",
                      id = "1",
                      firstName = "John",
                      lastName = "Smith",
                      nickName = "JS"
                  )
              ),
              noErrorNetworkResult.data
          )
          assertNull(noErrorNetworkResult.errors)

          var cacheResult = apolloClient.query(MeWithNickNameQuery())
              .fetchPolicyInterceptor(PartialCacheOnlyInterceptor)
              .execute()
          assertEquals(
              noErrorNetworkResult.data,
              cacheResult.data
          )
          assertNull(cacheResult.errors)

          apolloClient.query(MeWithNickNameQuery())
              .fetchPolicy(FetchPolicy.NetworkOnly)
              .execute()
          cacheResult = apolloClient.query(MeWithNickNameQuery())
              .fetchPolicyInterceptor(PartialCacheOnlyInterceptor)
              .execute()
          assertEquals(
              MeWithNickNameQuery.Data(
                  MeWithNickNameQuery.Me(
                      __typename = "User",
                      id = "1",
                      firstName = "Johnny",
                      lastName = "Smith",
                      nickName = "JS"
                  )
              ),
              cacheResult.data
          )
          assertNull(cacheResult.errors)

          apolloClient.query(MeWithNickNameQuery())
              .fetchPolicy(FetchPolicy.NetworkOnly)
              .errorsReplaceCachedValues(true)
              .execute()
          cacheResult = apolloClient.query(MeWithNickNameQuery())
              .fetchPolicyInterceptor(PartialCacheOnlyInterceptor)
              .execute()
          assertEquals(
              MeWithNickNameQuery.Data(
                  MeWithNickNameQuery.Me(
                      __typename = "User",
                      id = "1",
                      firstName = "Johnny Boy",
                      lastName = "Smith",
                      nickName = null
                  )
              ),
              cacheResult.data
          )
          assertErrorsEquals(
              listOf(
                  Error.Builder("'nickName' can't be reached").path(listOf("me", "nickName")).build()
              ),
              cacheResult.errors
          )
        }
  }

  @Test
  fun normalize() = runTest(before = { setUp() }, after = { tearDown() }) {
    val query = MeWithNickNameQuery()
    val dataWithErrors = MeWithNickNameQuery.Data(
        MeWithNickNameQuery.Me(
            __typename = "User",
            id = "1",
            firstName = "John",
            lastName = "Smith",
            nickName = null
        )
    ).withErrors(
        query,
        listOf(Error.Builder("'nickName' can't be reached").path(listOf("me", "nickName")).build()),
    )
    val normalized: Map<CacheKey, Record> = memoryCacheManager.normalize(
        executable = query,
        dataWithErrors = dataWithErrors,
    )
    assertEquals("User", normalized[CacheKey("User:1")]!!["__typename"])
    assertEquals("1", normalized[CacheKey("User:1")]!!["id"])
    assertEquals("John", normalized[CacheKey("User:1")]!!["firstName"])
    assertEquals("Smith", normalized[CacheKey("User:1")]!!["lastName"])
    assertErrorsEquals(Error.Builder("'nickName' can't be reached").path(listOf("me", "nickName"))
        .build(), normalized[CacheKey("User:1")]!!["nickName"] as Error
    )
  }

  @Test
  fun writeOperationMemory() = runTest(before = { setUp() }, after = { tearDown() }) {
    writeOperation(memoryCacheManager)
  }

  @Test
  fun writeOperationSql() = runTest(before = { setUp() }, after = { tearDown() }) {
    writeOperation(sqlCacheManager)
  }

  @Test
  fun writeOperationMemoryThenSql() = runTest(before = { setUp() }, after = { tearDown() }) {
    writeOperation(memoryThenSqlCacheManager)
  }

  private suspend fun writeOperation(cacheManager: CacheManager) {
    cacheManager.writeOperation(
        operation = MeWithNickNameQuery(),
        data = MeWithNickNameQuery.Data(
            MeWithNickNameQuery.Me(
                __typename = "User",
                id = "1",
                firstName = "John",
                lastName = "Smith",
                nickName = null
            )
        ),
        errors = listOf(
            Error.Builder("'nickName' can't be reached").path(listOf("me", "nickName")).build()
        ),
    )
    val responseFromCache = cacheManager.readOperation(MeWithNickNameQuery())
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
        responseFromCache.data,
    )
    assertErrorsEquals(
        listOf(
            Error.Builder("'nickName' can't be reached").path(listOf("me", "nickName")).build()
        ),
        responseFromCache.errors
    )
  }
}

val PartialCacheOnlyInterceptor = object : ApolloInterceptor {
  override fun <D : Operation.Data> intercept(request: ApolloRequest<D>, chain: ApolloInterceptorChain): Flow<ApolloResponse<D>> {
    return chain.proceed(
        request = request
            .newBuilder()
            .fetchFromCache(true)
            .throwOnCacheMiss(false)
            .serverErrorsAsCacheMisses(false)
            .build(),
    )
  }
}

