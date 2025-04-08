package test

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.ApolloRequest
import com.apollographql.apollo.api.ApolloResponse
import com.apollographql.apollo.api.Error
import com.apollographql.apollo.api.Operation
import com.apollographql.apollo.exception.CacheMissException
import com.apollographql.apollo.interceptor.ApolloInterceptor
import com.apollographql.apollo.interceptor.ApolloInterceptorChain
import com.apollographql.apollo.testing.QueueTestNetworkTransport
import com.apollographql.apollo.testing.enqueueTestResponse
import com.apollographql.cache.normalized.ApolloStore
import com.apollographql.cache.normalized.FetchPolicy
import com.apollographql.cache.normalized.api.ApolloCacheHeaders
import com.apollographql.cache.normalized.api.CacheHeaders
import com.apollographql.cache.normalized.cacheHeaders
import com.apollographql.cache.normalized.doNotStore
import com.apollographql.cache.normalized.fetchPolicy
import com.apollographql.cache.normalized.memory.MemoryCacheFactory
import com.apollographql.cache.normalized.store
import com.apollographql.cache.normalized.testing.runTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import normalizer.HeroNameQuery
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class CacheFlagsTest {
  private lateinit var apolloClient: ApolloClient
  private lateinit var store: ApolloStore

  private fun setUp() {
    store = ApolloStore(MemoryCacheFactory())
    apolloClient = ApolloClient.Builder().networkTransport(QueueTestNetworkTransport()).store(store).build()
  }

  @Test
  fun doNotStore() = runTest(before = { setUp() }) {
    val query = HeroNameQuery()
    val data = HeroNameQuery.Data(HeroNameQuery.Hero("R2-D2"))
    apolloClient.enqueueTestResponse(query, data)

    apolloClient.query(query).doNotStore(true).execute()

    // Since the previous request was not stored, this should fail
    assertIs<CacheMissException>(
        apolloClient.query(query).fetchPolicy(FetchPolicy.CacheOnly).execute().exception
    )
  }

  private val partialResponseData = HeroNameQuery.Data(null)
  private val partialResponseErrors = listOf(
      Error.Builder(message = "An error Happened")
          .locations(listOf(Error.Location(0, 0)))
          .build()
  )

  @Test
  fun storePartialResponse() = runTest(before = { setUp() }) {
    val query = HeroNameQuery()
    apolloClient.enqueueTestResponse(query, partialResponseData, partialResponseErrors)

    // this should store the response
    apolloClient.query(query).execute()

    val response = apolloClient.query(query).fetchPolicy(FetchPolicy.CacheOnly).execute()
    assertNotNull(response.data)
  }


  @Test
  fun doNotStoreWhenSetInResponse() = runTest(before = { setUp() }) {
    val query = HeroNameQuery()
    val data = HeroNameQuery.Data(HeroNameQuery.Hero("R2-D2"))
    apolloClient = apolloClient.newBuilder().addInterceptor(object : ApolloInterceptor {
      override fun <D : Operation.Data> intercept(request: ApolloRequest<D>, chain: ApolloInterceptorChain): Flow<ApolloResponse<D>> {
        return chain.proceed(request).map { response ->
          response.newBuilder().cacheHeaders(CacheHeaders.Builder().addHeader(ApolloCacheHeaders.DO_NOT_STORE, "").build()).build()
        }
      }
    }).build()
    apolloClient.enqueueTestResponse(query, data)

    apolloClient.query(query).fetchPolicy(FetchPolicy.NetworkFirst).execute()

    // Since the previous request was not stored, this should fail
    assertIs<CacheMissException>(
        apolloClient.query(query).fetchPolicy(FetchPolicy.CacheOnly).execute().exception
    )
  }
}
