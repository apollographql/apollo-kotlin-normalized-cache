package test

import com.apollographql.apollo.ApolloClient.Builder
import com.apollographql.apollo.exception.CacheMissException
import com.apollographql.cache.normalized.FetchPolicy
import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.api.FieldPolicies
import com.apollographql.cache.normalized.fetchPolicy
import com.apollographql.cache.normalized.memory.MemoryCacheFactory
import com.apollographql.cache.normalized.normalizedCache
import com.apollographql.cache.normalized.testing.runTest
import com.apollographql.mockserver.MockServer
import com.apollographql.mockserver.enqueueString
import okio.use
import test.UserByIdQuery.Data
import test.UserByIdQuery.User
import test.cache.Cache
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration

class CacheConfigurationTest {
  private lateinit var mockServer: MockServer

  private fun setUp() {
    mockServer = MockServer()
  }

  private fun tearDown() {
    mockServer.close()
  }

  @Test
  fun noKeyArgCacheHit() = runTest(before = { setUp() }, after = { tearDown() }) {
    mockServer.enqueueString(
        // language=JSON
        """
          {
            "data": {
              "user": {
                "__typename": "User",
                "id": "42",
                "firstName": "John",
                "lastName": "Smith"
              }
            }
          }
        """.trimIndent(),
    )
    Builder()
        .serverUrl(mockServer.url())
        .configureCache(
            fieldPolicies = emptyMap(),
        )
        .build()
        .use { apolloClient ->
          val networkResult = apolloClient.query(UserByIdQuery("42"))
              .fetchPolicy(FetchPolicy.NetworkOnly)
              .execute()
          assertEquals(
              Data(
                  User(
                      __typename = "User",
                      id = "42",
                      firstName = "John",
                      lastName = "Smith",
                  ),
              ),
              networkResult.data,
          )

          val cacheResult = apolloClient.query(UserByIdQuery("42"))
              .fetchPolicy(FetchPolicy.CacheOnly)
              .execute()
          assertEquals(
              networkResult.data,
              cacheResult.data,
          )
        }
  }

  @Test
  fun noKeyArgCacheMiss() = runTest(before = { setUp() }, after = { tearDown() }) {
    mockServer.enqueueString(
        // language=JSON
        """
          {
            "data": {
              "user": {
                "__typename": "User",
                "id": "42",
                "firstName": "John",
                "lastName": "Smith"
              }
            }
          }
        """.trimIndent(),
    )
    Builder()
        .serverUrl(mockServer.url())
        .configureCache(
            fieldPolicies = emptyMap(),
        )
        .build()
        .use { apolloClient ->
          val networkResult = apolloClient.query(UserByIdQuery("42"))
              .fetchPolicy(FetchPolicy.NetworkOnly)
              .execute()
          assertEquals(
              Data(
                  User(
                      __typename = "User",
                      id = "42",
                      firstName = "John",
                      lastName = "Smith",
                  ),
              ),
              networkResult.data,
          )

          val cacheResult = apolloClient.query(UserByIdQuery("43"))
              .fetchPolicy(FetchPolicy.CacheOnly)
              .execute()
          assertIs<CacheMissException>(cacheResult.exception)
          assertEquals(
              "Object 'QUERY_ROOT' has no field named 'user({\"id\":\"43\"})'",
              cacheResult.exception!!.message,
          )
        }
  }

  @Test
  fun wrongKeyArgCacheHit() = runTest(before = { setUp() }, after = { tearDown() }) {
    mockServer.enqueueString(
        // language=JSON
        """
          {
            "data": {
              "user": {
                "__typename": "User",
                "id": "42",
                "firstName": "John",
                "lastName": "Smith"
              }
            }
          }
        """.trimIndent(),
    )
    Builder()
        .serverUrl(mockServer.url())
        .configureCache(
            fieldPolicies = mapOf(
                "Query" to FieldPolicies(
                    fieldPolicies = mapOf(
                        "user" to FieldPolicies.FieldPolicy(
                            keyArgs = listOf(
                                "wrongId",
                            ),
                        ),
                    ),
                ),
            ),
        )
        .build()
        .use { apolloClient ->
          val networkResult = apolloClient.query(UserByIdQuery("42"))
              .fetchPolicy(FetchPolicy.NetworkOnly)
              .execute()
          assertEquals(
              Data(
                  User(
                      __typename = "User",
                      id = "42",
                      firstName = "John",
                      lastName = "Smith",
                  ),
              ),
              networkResult.data,
          )

          val cacheResult = apolloClient.query(UserByIdQuery("42"))
              .fetchPolicy(FetchPolicy.CacheOnly)
              .execute()
          assertEquals(
              networkResult.data,
              cacheResult.data,
          )
        }
  }

  @Test
  fun wrongKeyArgCacheMiss() = runTest(before = { setUp() }, after = { tearDown() }) {
    mockServer.enqueueString(
        // language=JSON
        """
          {
            "data": {
              "user": {
                "__typename": "User",
                "id": "42",
                "firstName": "John",
                "lastName": "Smith"
              }
            }
          }
        """.trimIndent(),
    )
    Builder()
        .serverUrl(mockServer.url())
        .configureCache(
            fieldPolicies = mapOf(
                "Query" to FieldPolicies(
                    fieldPolicies = mapOf(
                        "user" to FieldPolicies.FieldPolicy(
                            keyArgs = listOf(
                                "wrongId",
                            ),
                        ),
                    ),
                ),
            ),
        )
        .build()
        .use { apolloClient ->
          val networkResult = apolloClient.query(UserByIdQuery("42"))
              .fetchPolicy(FetchPolicy.NetworkOnly)
              .execute()
          assertEquals(
              Data(
                  User(
                      __typename = "User",
                      id = "42",
                      firstName = "John",
                      lastName = "Smith",
                  ),
              ),
              networkResult.data,
          )

          val cacheResult = apolloClient.query(UserByIdQuery("43"))
              .fetchPolicy(FetchPolicy.CacheOnly)
              .execute()
          assertEquals(
              "Object 'QUERY_ROOT' has no field named 'user({\"id\":\"43\"})'",
              cacheResult.exception!!.message,
          )
        }
  }

}

private fun Builder.configureCache(
    fieldPolicies: Map<String, FieldPolicies>,
): Builder = apply {
  normalizedCache(
      normalizedCacheFactory = MemoryCacheFactory(),
      typePolicies = Cache.typePolicies,
      fieldPolicies = fieldPolicies,
      connectionTypes = Cache.connectionTypes,
      embeddedFields = Cache.embeddedFields,
      maxAges = Cache.maxAges,
      defaultMaxAge = Duration.INFINITE,
      keyScope = CacheKey.Scope.TYPE,
      enableOptimisticUpdates = false,
      writeToCacheAsynchronously = false,
  )
}
