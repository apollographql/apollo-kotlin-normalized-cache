package test

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.ApolloRequest
import com.apollographql.apollo.api.ApolloResponse
import com.apollographql.apollo.api.Error
import com.apollographql.apollo.api.Operation
import com.apollographql.apollo.interceptor.ApolloInterceptor
import com.apollographql.apollo.interceptor.ApolloInterceptorChain
import com.apollographql.cache.normalized.FetchPolicy
import com.apollographql.cache.normalized.api.CacheHeaders
import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.api.DefaultFieldKeyGenerator
import com.apollographql.cache.normalized.api.FieldKeyContext
import com.apollographql.cache.normalized.api.FieldKeyGenerator
import com.apollographql.cache.normalized.api.NormalizedCacheFactory
import com.apollographql.cache.normalized.api.SchemaCoordinatesMaxAgeProvider
import com.apollographql.cache.normalized.apolloStore
import com.apollographql.cache.normalized.fetchFromCache
import com.apollographql.cache.normalized.fetchPolicy
import com.apollographql.cache.normalized.fetchPolicyInterceptor
import com.apollographql.cache.normalized.memory.MemoryCacheFactory
import com.apollographql.cache.normalized.normalizedCache
import com.apollographql.cache.normalized.testing.SqlNormalizedCacheFactory
import com.apollographql.cache.normalized.testing.append
import com.apollographql.cache.normalized.testing.assertErrorsEquals
import com.apollographql.cache.normalized.testing.keyToString
import com.apollographql.cache.normalized.testing.runTest
import com.apollographql.mockserver.MockServer
import com.apollographql.mockserver.enqueueString
import donotstore.GetUserQuery
import donotstore.SignInMutation
import donotstore.cache.Cache
import kotlinx.coroutines.flow.Flow
import okio.use
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class DoNotStoreTest {
  private lateinit var mockServer: MockServer

  private fun setUp() {
    mockServer = MockServer()
  }

  private fun tearDown() {
    mockServer.close()
  }

  @Test
  fun doNotStoreQueryMemory() {
    doNotStoreQuery(MemoryCacheFactory())
  }

  @Test
  fun doNotStoreQuerySql() {
    doNotStoreQuery(SqlNormalizedCacheFactory())
  }

  @Test
  fun doNotStoreQueryChained() {
    doNotStoreQuery(MemoryCacheFactory().chain(SqlNormalizedCacheFactory()))
  }

  private fun doNotStoreQuery(normalizedCacheFactory: NormalizedCacheFactory) = runTest(before = { setUp() }, after = { tearDown() }) {
    mockServer.enqueueString(
        // language=JSON
        """
        {
          "data": {
            "user": {
              "__typename": "User",
              "id": "42",
              "name": "John",
              "email": "john@example.com",
              "sensitiveScalar": "authToken",
              "sensitiveObject": {
                "__typename": "SensitiveObject",
                "password": "password"
              },
              "project": {
                "__typename": "Project",
                "name": "Stardust"              
              }
            }
          }
        }
        """
    )
    val maxAgeProvider = SchemaCoordinatesMaxAgeProvider(
        Cache.maxAges,
        defaultMaxAge = 20.seconds,
    )
    ApolloClient.Builder()
        .serverUrl(mockServer.url())
        .normalizedCache(
            normalizedCacheFactory = normalizedCacheFactory,
            maxAgeProvider = maxAgeProvider,
        )
        .build()
        .use { apolloClient ->
          apolloClient.apolloStore.clearAll()
          val networkResponse = apolloClient.query(GetUserQuery())
              .fetchPolicy(FetchPolicy.NetworkOnly)
              .execute()
          assertEquals(
              GetUserQuery.Data(
                  GetUserQuery.User(
                      __typename = "User",
                      id = "42",
                      name = "John",
                      email = "john@example.com",
                      sensitiveScalar = "authToken",
                      sensitiveObject = GetUserQuery.SensitiveObject(__typename = "SensitiveObject", password = "password"),
                      project = GetUserQuery.Project(
                          __typename = "Project",
                          name = "Stardust",
                      )
                  )
              ),
              networkResponse.data
          )
          val cacheResponse = apolloClient.query(GetUserQuery())
              .fetchPolicyInterceptor(PartialCacheOnlyInterceptor)
              .execute()
          assertErrorsEquals(
              listOf(
                  Error.Builder("Object '${CacheKey("User:42").keyToString()}' has no field named 'sensitiveScalar' in the cache")
                      .path(listOf("user", "sensitiveScalar")).build(),
                  Error.Builder("Object '${CacheKey("User:42").keyToString()}' has no field named 'sensitiveObject' in the cache")
                      .path(listOf("user", "sensitiveObject")).build(),
                  Error.Builder("Object '${CacheKey("User:42").keyToString()}' has no field named 'project' in the cache")
                      .path(listOf("user", "project")).build(),
              ),
              cacheResponse.errors
          )
        }
  }

  @Test
  fun doNotStoreMutationMemory() {
    doNotStoreMutation(MemoryCacheFactory())
  }

  @Test
  fun doNotStoreMutationSql() {
    doNotStoreMutation(SqlNormalizedCacheFactory())
  }

  @Test
  fun doNotStoreMutationChained() {
    doNotStoreMutation(MemoryCacheFactory().chain(SqlNormalizedCacheFactory()))
  }

  private fun doNotStoreMutation(normalizedCacheFactory: NormalizedCacheFactory) = runTest(before = { setUp() }, after = { tearDown() }) {
    mockServer.enqueueString(
        // language=JSON
        """
        {
          "data": {
            "auth": {
              "__typename": "Auth",
              "signIn": {
                "__typename": "SignIn",
                "token": "aaaabbbbccc",
                "userData": {
                  "__typename": "User",
                  "id": "42",
                  "name": "John",
                  "email": "john@example.com"
                }
              }
            }
          }
        }
        """
    )
    val maxAgeProvider = SchemaCoordinatesMaxAgeProvider(
        Cache.maxAges,
        defaultMaxAge = 20.seconds,
    )

    // Do not store passwords in the cache
    val fieldKeyGenerator = object : FieldKeyGenerator {
      override fun getFieldKey(context: FieldKeyContext): String {
        if (context.parentType == "Auth" && context.field.name == "signIn") {
          return "signIn"
        }
        return DefaultFieldKeyGenerator.getFieldKey(context)
      }
    }
    ApolloClient.Builder()
        .serverUrl(mockServer.url())
        .normalizedCache(
            normalizedCacheFactory = normalizedCacheFactory,
            maxAgeProvider = maxAgeProvider,
            fieldKeyGenerator = fieldKeyGenerator
        )
        .build()
        .use { apolloClient ->
          apolloClient.apolloStore.clearAll()
          val networkResponse = apolloClient.mutation(SignInMutation("scott", "tiger"))
              .fetchPolicy(FetchPolicy.NetworkOnly)
              .execute()
          assertEquals(
              SignInMutation.Data(
                  SignInMutation.Auth(
                      __typename = "Auth",
                      SignInMutation.SignIn(
                          __typename = "SignIn",
                          token = "aaaabbbbccc",
                          userData = SignInMutation.UserData(
                              __typename = "User",
                              id = "42",
                              userData = donotstore.fragment.UserData(
                                  id = "42",
                                  name = "John",
                                  email = "john@example.com",
                                  __typename = "User"

                              )
                          )
                      )
                  )
              ),
              networkResponse.data
          )

          apolloClient.apolloStore.accessCache { cache ->
            val authRecord = cache.loadRecord(CacheKey.MUTATION_ROOT.append("auth"), CacheHeaders.NONE)!!
            // No password in field key
            assertContentEquals(listOf("__typename", "signIn"), authRecord.fields.keys)

            val signInRecord = cache.loadRecord(CacheKey.MUTATION_ROOT.append("auth", "signIn"), CacheHeaders.NONE)!!
            // No token in record
            assertContentEquals(listOf("__typename", "userData"), signInRecord.fields.keys)
          }
        }
  }
}

private val PartialCacheOnlyInterceptor = object : ApolloInterceptor {
  override fun <D : Operation.Data> intercept(request: ApolloRequest<D>, chain: ApolloInterceptorChain): Flow<ApolloResponse<D>> {
    return chain.proceed(
        request = request
            .newBuilder()
            .fetchFromCache(true)
            .build()
    )
  }
}
