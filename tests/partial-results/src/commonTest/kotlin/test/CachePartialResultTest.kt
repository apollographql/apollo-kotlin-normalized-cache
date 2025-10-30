package test

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.ApolloRequest
import com.apollographql.apollo.api.ApolloResponse
import com.apollographql.apollo.api.Error
import com.apollographql.apollo.api.Operation
import com.apollographql.apollo.api.graphQLErrorOrNull
import com.apollographql.apollo.interceptor.ApolloInterceptor
import com.apollographql.apollo.interceptor.ApolloInterceptorChain
import com.apollographql.cache.normalized.CacheManager
import com.apollographql.cache.normalized.FetchPolicy
import com.apollographql.cache.normalized.api.CacheControlCacheResolver
import com.apollographql.cache.normalized.api.CacheHeaders
import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.api.DefaultRecordMerger
import com.apollographql.cache.normalized.api.FieldPolicyCacheResolver
import com.apollographql.cache.normalized.api.IdCacheKeyGenerator
import com.apollographql.cache.normalized.api.IdCacheResolver
import com.apollographql.cache.normalized.api.Record
import com.apollographql.cache.normalized.api.SchemaCoordinatesMaxAgeProvider
import com.apollographql.cache.normalized.api.TypePolicyCacheKeyGenerator
import com.apollographql.cache.normalized.apolloStore
import com.apollographql.cache.normalized.cacheManager
import com.apollographql.cache.normalized.fetchFromCache
import com.apollographql.cache.normalized.fetchPolicy
import com.apollographql.cache.normalized.fetchPolicyInterceptor
import com.apollographql.cache.normalized.memory.MemoryCacheFactory
import com.apollographql.cache.normalized.normalizedCache
import com.apollographql.cache.normalized.storeReceivedDate
import com.apollographql.cache.normalized.testing.append
import com.apollographql.cache.normalized.testing.assertErrorsEquals
import com.apollographql.cache.normalized.testing.keyToString
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
import kotlin.time.Duration

class CachePartialResultTest {
  private lateinit var mockServer: MockServer

  private fun setUp() {
    mockServer = MockServer()
  }

  private fun tearDown() {
    mockServer.close()
  }

  @Test
  fun simple() = runTest(before = { setUp() }, after = { tearDown() }) {
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
              "email": "jsmith@example.com"
            }
          }
        }
        """
    )
    ApolloClient.Builder()
        .serverUrl(mockServer.url())
        .normalizedCache(MemoryCacheFactory(), cacheKeyGenerator = TypePolicyCacheKeyGenerator(Cache.typePolicies), cacheResolver = FieldPolicyCacheResolver(Cache.fieldPolicies))
        .build()
        .use { apolloClient ->
          val networkResult = apolloClient.query(MeWithoutNickNameWithEmailQuery())
              .fetchPolicy(FetchPolicy.NetworkOnly)
              .execute()
          assertEquals(
              MeWithoutNickNameWithEmailQuery.Data(
                  MeWithoutNickNameWithEmailQuery.Me(
                      __typename = "User",
                      firstName = "John",
                      lastName = "Smith",
                      email = "jsmith@example.com",
                      id = "1",
                      onUser = MeWithoutNickNameWithEmailQuery.OnUser(
                          id = "1"
                      )
                  )
              ),
              networkResult.data
          )

          val cacheResult = apolloClient.query(MeWithoutNickNameWithoutEmailQuery())
              .fetchPolicyInterceptor(PartialCacheOnlyInterceptor)
              .execute()
          assertEquals(
              MeWithoutNickNameWithoutEmailQuery.Data(
                  MeWithoutNickNameWithoutEmailQuery.Me(
                      id = "1",
                      firstName = "John",
                      lastName = "Smith",
                      __typename = "User"
                  )
              ),
              cacheResult.data
          )

          val cacheMissResult = apolloClient.query(MeWithNickNameQuery())
              .fetchPolicyInterceptor(PartialCacheOnlyInterceptor)
              .execute()
          assertEquals(
              MeWithNickNameQuery.Data(
                  MeWithNickNameQuery.Me(
                      id = "1",
                      firstName = "John",
                      lastName = "Smith",
                      nickName = null,
                      __typename = "User"
                  )
              ),
              cacheMissResult.data
          )
          assertErrorsEquals(
              listOf(
                  Error.Builder("Object '${CacheKey("User:1").keyToString()}' has no field named 'nickName' in the cache")
                      .path(listOf("me", "nickName"))
                      .build()
              ),
              cacheMissResult.errors
          )
        }
  }

  @Test
  fun lists() = runTest(before = { setUp() }, after = { tearDown() }) {
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
        .cacheManager(
            CacheManager(
                normalizedCacheFactory = MemoryCacheFactory(),
                cacheKeyGenerator = IdCacheKeyGenerator(),
                cacheResolver = IdCacheResolver(),
            ),
        )
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

          val cacheResult = apolloClient.query(UsersQuery(listOf("1", "2", "3")))
              .fetchPolicyInterceptor(PartialCacheOnlyInterceptor)
              .execute()
          assertEquals(
              networkResult.data,
              cacheResult.data,
          )
          assertErrorsEquals(
              listOf(
                  Error.Builder("User `3` not found").path(listOf("users", 2)).build()
              ),
              cacheResult.errors
          )
        }
  }

  @Test
  fun composite() = runTest(before = { setUp() }, after = { tearDown() }) {
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
              "bestFriend": {
                "__typename": "User",
                "id": "2",
                "firstName": "Jane",
                "lastName": "Doe"
              },
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
          }
        }
        """
    )
    ApolloClient.Builder()
        .serverUrl(mockServer.url())
        .normalizedCache(MemoryCacheFactory(), cacheKeyGenerator = TypePolicyCacheKeyGenerator(Cache.typePolicies), cacheResolver = FieldPolicyCacheResolver(Cache.fieldPolicies))
        .build()
        .use { apolloClient ->
          // Prime the cache
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
                      bestFriend = MeWithBestFriendQuery.BestFriend(
                          __typename = "User",
                          id = "2",
                          firstName = "Jane",
                          lastName = "Doe"
                      ),
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

          // Remove project lead from the cache
          apolloClient.apolloStore.remove(CacheKey("User:3"))
          val cacheResult = apolloClient.query(MeWithBestFriendQuery())
              .fetchPolicyInterceptor(PartialCacheOnlyInterceptor)
              .execute()
          assertEquals(
              MeWithBestFriendQuery.Data(
                  MeWithBestFriendQuery.Me(
                      __typename = "User",
                      id = "1",
                      firstName = "John",
                      lastName = "Smith",
                      bestFriend = MeWithBestFriendQuery.BestFriend(
                          __typename = "User",
                          id = "2",
                          firstName = "Jane",
                          lastName = "Doe"
                      ),
                      projects = listOf(
                          MeWithBestFriendQuery.Project(
                              __typename = "Project",
                              lead = null,
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
              cacheResult.data
          )
          assertErrorsEquals(
              listOf(
                  Error.Builder("Object '${CacheKey("User:3").keyToString()}' not found in the cache")
                      .path(listOf("me", "projects", 0, "lead"))
                      .build()
              ),
              cacheResult.errors
          )

          // Remove best friend from the cache
          apolloClient.apolloStore.remove(CacheKey("User:2"))
          val cacheResult2 = apolloClient.query(MeWithBestFriendQuery())
              .fetchPolicyInterceptor(PartialCacheOnlyInterceptor)
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
                              lead = null,
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
              cacheResult2.data
          )
          assertErrorsEquals(
              listOf(
                  Error.Builder("Object '${CacheKey("User:2").keyToString()}' not found in the cache").path(listOf("me", "bestFriend"))
                      .build(),
                  Error.Builder("Object '${CacheKey("User:3").keyToString()}' not found in the cache")
                      .path(listOf("me", "projects", 0, "lead"))
                      .build(),
              ),
              cacheResult2.errors
          )

          // Remove project user from the cache
          apolloClient.apolloStore.remove(CacheKey("User:4"))
          val cacheResult3 = apolloClient.query(MeWithBestFriendQuery())
              .fetchPolicyInterceptor(PartialCacheOnlyInterceptor)
              .execute()
          // Due to null bubbling the whole data is null
          assertNull(cacheResult3.data)
          assertErrorsEquals(
              listOf(
                  Error.Builder("Object '${CacheKey("User:2").keyToString()}' not found in the cache").path(listOf("me", "bestFriend"))
                      .build(),
                  Error.Builder("Object '${CacheKey("User:3").keyToString()}' not found in the cache")
                      .path(listOf("me", "projects", 0, "lead"))
                      .build(),
                  Error.Builder("Object '${CacheKey("User:4").keyToString()}' not found in the cache")
                      .path(listOf("me", "projects", 0, "users", 0))
                      .build()
              ),
              cacheResult3.errors
          )
        }
  }

  @Test
  fun argumentsAndAliases() = runTest(before = { setUp() }, after = { tearDown() }) {
    mockServer.enqueueString(
        // language=JSON
        """
        {
          "data": {
            "project": {
              "__typename": "Project",
              "id": "42",
              "name": "El Dorado",
              "description": "The lost city of gold"
            },
            "project2": {
              "__typename": "Project",
              "id": "44",
              "name": "Atlantis",
              "description": "The lost city of water"
            }
          }
        }
        """
    )
    ApolloClient.Builder()
        .serverUrl(mockServer.url())
        .cacheManager(
            CacheManager(
                normalizedCacheFactory = MemoryCacheFactory(),
                cacheKeyGenerator = IdCacheKeyGenerator(),
                cacheResolver = IdCacheResolver(),
            ),
        )
        .build()
        .use { apolloClient ->
          val networkResult = apolloClient.query(DefaultProjectQuery())
              .fetchPolicy(FetchPolicy.NetworkOnly)
              .execute()
          assertEquals(
              DefaultProjectQuery.Data(
                  project = DefaultProjectQuery.Project(
                      __typename = "Project",
                      id = "42",
                      name = "El Dorado",
                      description = "The lost city of gold"
                  ),
                  project2 = DefaultProjectQuery.Project2(
                      __typename = "Project",
                      id = "44",
                      name = "Atlantis",
                      description = "The lost city of water"
                  )
              ),
              networkResult.data
          )

          val cacheResult = apolloClient.query(DefaultProjectQuery())
              .fetchPolicyInterceptor(PartialCacheOnlyInterceptor)
              .execute()
          assertEquals(
              networkResult.data,
              cacheResult.data
          )
        }
  }

  @Test
  fun customScalar() = runTest(before = { setUp() }, after = { tearDown() }) {
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
              "category": {
                "code": 1,
                "name": "First"
              },
              "moreInfo": [0, "no", false, {}, []]
            }
          }
        }
        """
    )
    ApolloClient.Builder()
        .serverUrl(mockServer.url())
        .cacheManager(
            CacheManager(
                normalizedCacheFactory = MemoryCacheFactory(),
                cacheKeyGenerator = IdCacheKeyGenerator(),
                cacheResolver = IdCacheResolver(),
            ),
        )
        .build()
        .use { apolloClient ->
          val networkResult = apolloClient.query(UserByCategoryQuery(Category(2, "Second")))
              .fetchPolicy(FetchPolicy.NetworkOnly)
              .execute()
          assertEquals(
              UserByCategoryQuery.Data(
                  UserByCategoryQuery.User(
                      firstName = "John",
                      lastName = "Smith",
                      category = Category(
                          code = 1,
                          name = "First"
                      ),
                      moreInfo = listOf(0, "no", false, mapOf<String, Any?>(), emptyList<Any?>()),
                      id = "1",
                      __typename = "User",
                  )
              ),
              networkResult.data
          )

          val cacheResult = apolloClient.query(UserByCategoryQuery(Category(2, "Second")))
              .fetchPolicyInterceptor(PartialCacheOnlyInterceptor)
              .execute()
          assertEquals(
              networkResult.data,
              cacheResult.data
          )

          // Remove the category from the cache
          apolloClient.apolloStore.accessCache { cache ->
            val record = cache.loadRecord(CacheKey("User:1"), CacheHeaders.NONE)!!
            cache.remove(CacheKey("User:1"), false)
            cache.merge(Record(record.key, record.fields - "category"), CacheHeaders.NONE, DefaultRecordMerger)
          }
          val cacheMissResult = apolloClient.query(UserByCategoryQuery(Category(2, "Second")))
              .fetchPolicyInterceptor(PartialCacheOnlyInterceptor)
              .execute()
          // Due to null bubbling the whole data is null
          assertNull(cacheMissResult.data)
          assertErrorsEquals(
              listOf(
                  Error.Builder("Object '${CacheKey("User:1").keyToString()}' has no field named 'category' in the cache")
                      .path(listOf("user", "category"))
                      .build()
              ),
              cacheMissResult.errors
          )
        }
  }

  @Test
  fun fragmentsAndAliases() = runTest(before = { setUp() }, after = { tearDown() }) {
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
              }
            }
          }
        }
        """
    )
    ApolloClient.Builder()
        .serverUrl(mockServer.url())
        .normalizedCache(MemoryCacheFactory(), cacheKeyGenerator = TypePolicyCacheKeyGenerator(Cache.typePolicies), cacheResolver = FieldPolicyCacheResolver(Cache.fieldPolicies))
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
                      ),
                  )
              ),
              networkResult.data
          )

          val cacheResult = apolloClient.query(WithFragmentsQuery())
              .fetchPolicyInterceptor(PartialCacheOnlyInterceptor)
              .execute()
          assertEquals(
              networkResult.data,
              cacheResult.data
          )

          // Remove lead from the cache
          apolloClient.apolloStore.remove(CacheKey("User:2"))

          val cacheMissResult = apolloClient.query(WithFragmentsQuery())
              .fetchPolicyInterceptor(PartialCacheOnlyInterceptor)
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
                          lead0 = null,
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
                      ),
                  )
              ),
              cacheMissResult.data
          )
          assertErrorsEquals(
              listOf(
                  Error.Builder("Object '${CacheKey("User:2").keyToString()}' not found in the cache")
                      .path(listOf("me", "mainProject", "lead0"))
                      .build()
              ),
              cacheMissResult.errors
          )
        }
  }

  @Test
  fun cacheControl() = runTest(before = { setUp() }, after = { tearDown() }) {
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
    ApolloClient.Builder()
        .serverUrl(mockServer.url())
        .normalizedCache(MemoryCacheFactory(), cacheResolver = CacheControlCacheResolver(SchemaCoordinatesMaxAgeProvider(Cache.maxAges, Duration.INFINITE), FieldPolicyCacheResolver(Cache.fieldPolicies)), cacheKeyGenerator = TypePolicyCacheKeyGenerator(Cache.typePolicies))
        .storeReceivedDate(true)
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
                      nickName = "JS"
                  )
              ),
              networkResult.data
          )

          val cacheMissResult = apolloClient.query(MeWithNickNameQuery())
              .fetchPolicyInterceptor(PartialCacheOnlyInterceptor)
              .execute()
          assertEquals(
              MeWithNickNameQuery.Data(
                  MeWithNickNameQuery.Me(
                      id = "1",
                      firstName = "John",
                      lastName = "Smith",
                      nickName = null,
                      __typename = "User"
                  )
              ),
              cacheMissResult.data
          )
          assertErrorsEquals(
              listOf(
                  Error.Builder("Field 'nickName' on object '${CacheKey("User:1").keyToString()}' is stale in the cache")
                      .path(listOf("me", "nickName"))
                      .build()
              ),
              cacheMissResult.errors
          )
        }
  }

  @Test
  fun cacheControlWithSemanticNonNull() = runTest(before = { setUp() }, after = { tearDown() }) {
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
              "employeeInfo": {
                "__typename": "EmployeeInfo",
                "id": "1",
                "salary": 100000,
                "department": "Engineering"
              }
            }
          }
        }
        """
    )
    ApolloClient.Builder()
        .serverUrl(mockServer.url())
        .normalizedCache(MemoryCacheFactory(), cacheKeyGenerator = TypePolicyCacheKeyGenerator(Cache.typePolicies), cacheResolver = CacheControlCacheResolver(SchemaCoordinatesMaxAgeProvider(Cache.maxAges, Duration.INFINITE), FieldPolicyCacheResolver(Cache.fieldPolicies)))
        .storeReceivedDate(true)
        .build()
        .use { apolloClient ->
          val networkResult = apolloClient.query(MeWithEmployeeInfoQuery())
              .fetchPolicy(FetchPolicy.NetworkOnly)
              .execute()
          assertEquals(
              MeWithEmployeeInfoQuery.Data(
                  MeWithEmployeeInfoQuery.Me(
                      __typename = "User",
                      id = "1",
                      firstName = "John",
                      lastName = "Smith",
                      employeeInfo = MeWithEmployeeInfoQuery.EmployeeInfo(
                          __typename = "EmployeeInfo",
                          id = "1",
                          salary = 100000,
                          department = "Engineering"
                      )
                  )
              ),
              networkResult.data
          )

          val cacheMissResult = apolloClient.query(MeWithEmployeeInfoQuery())
              .fetchPolicyInterceptor(PartialCacheOnlyInterceptor)
              .execute()
          assertEquals(
              MeWithEmployeeInfoQuery.Data(null),
              cacheMissResult.data
          )
          assertErrorsEquals(
              listOf(
                  Error.Builder("Field 'salary' on object '${
                    CacheKey("User:1").append("employeeInfo").keyToString()
                  }' is stale in the cache"
                  )
                      .path(listOf("me", "employeeInfo", "salary")).build()
              ),
              cacheMissResult.errors
          )
        }
  }

  @Test
  fun cacheControlWithCatchToResult() = runTest(before = { setUp() }, after = { tearDown() }) {
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
              "departmentInfo": {
                "__typename": "DepartmentInfo",
                "id": "1",
                "name": "Engineering"
              }
            }
          }
        }
        """
    )
    ApolloClient.Builder()
        .serverUrl(mockServer.url())
        .normalizedCache(MemoryCacheFactory(), cacheKeyGenerator = TypePolicyCacheKeyGenerator(Cache.typePolicies), cacheResolver = CacheControlCacheResolver(SchemaCoordinatesMaxAgeProvider(Cache.maxAges, Duration.INFINITE), FieldPolicyCacheResolver(Cache.fieldPolicies)))
        .storeReceivedDate(true)
        .build()
        .use { apolloClient ->
          apolloClient.query(MeWithDepartmentInfoQuery())
              .fetchPolicy(FetchPolicy.NetworkOnly)
              .execute()
          val cacheMissResult = apolloClient.query(MeWithDepartmentInfoQuery())
              .fetchPolicyInterceptor(PartialCacheOnlyInterceptor)
              .execute()
          assertErrorsEquals(
              Error.Builder("Field 'name' on object '${CacheKey("User:1").append("departmentInfo").keyToString()}' is stale in the cache")
                  .path(listOf("me", "departmentInfo", "name")).build(),
              cacheMissResult.data?.me?.departmentInfo?.name?.graphQLErrorOrNull()
          )
        }
  }
}

val PartialCacheOnlyInterceptor = object : ApolloInterceptor {
  override fun <D : Operation.Data> intercept(request: ApolloRequest<D>, chain: ApolloInterceptorChain): Flow<ApolloResponse<D>> {
    return chain.proceed(
        request = request
            .newBuilder()
            .fetchFromCache(true)
            .build()
    )
  }
}
