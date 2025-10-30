package test

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.testing.QueueTestNetworkTransport
import com.apollographql.apollo.testing.enqueueTestResponse
import com.apollographql.cache.normalized.CacheManager
import com.apollographql.cache.normalized.FetchPolicy
import com.apollographql.cache.normalized.api.IdCacheKeyGenerator
import com.apollographql.cache.normalized.api.IdCacheResolver
import com.apollographql.cache.normalized.cacheManager
import com.apollographql.cache.normalized.fetchPolicy
import com.apollographql.cache.normalized.memory.MemoryCacheFactory
import com.apollographql.cache.normalized.testing.runTest
import main.GetUser2Query
import main.GetUserByIdQuery
import main.GetUsersByIDsQuery
import main.GetUsersQuery
import kotlin.test.Test
import kotlin.test.assertEquals

class IdCacheKeyGeneratorTest {
  @Test
  fun defaultValues() = runTest {
    val cacheManager = CacheManager(
        normalizedCacheFactory = MemoryCacheFactory(),
        cacheKeyGenerator = IdCacheKeyGenerator(),
        cacheResolver = IdCacheResolver(),
    )
    val apolloClient = ApolloClient.Builder().networkTransport(QueueTestNetworkTransport()).cacheManager(cacheManager).build()
    val query = GetUser2Query("42")
    apolloClient.enqueueTestResponse(query, GetUser2Query.Data(GetUser2Query.User2(__typename = "User", id = "42", name = "John", email = "a@a.com")))
    apolloClient.query(query).fetchPolicy(FetchPolicy.NetworkOnly).execute()
    val user = apolloClient.query(query).fetchPolicy(FetchPolicy.CacheOnly).execute().dataOrThrow().user2!!
    assertEquals("John", user.name)
    assertEquals("a@a.com", user.email)
  }

  @Test
  fun customIdField() = runTest {
    val cacheManager = CacheManager(
        normalizedCacheFactory = MemoryCacheFactory(),
        cacheKeyGenerator = IdCacheKeyGenerator("userId"),
        cacheResolver = IdCacheResolver(listOf("userId")),
    )
    val apolloClient = ApolloClient.Builder().networkTransport(QueueTestNetworkTransport()).cacheManager(cacheManager).build()
    val query = GetUserByIdQuery("42")
    apolloClient.enqueueTestResponse(query, GetUserByIdQuery.Data(GetUserByIdQuery.UserById(__typename = "User", userId = "42", name = "John", email = "a@a.com")))
    apolloClient.query(query).fetchPolicy(FetchPolicy.NetworkOnly).execute()
    val user = apolloClient.query(query).fetchPolicy(FetchPolicy.CacheOnly).execute().dataOrThrow().userById!!
    assertEquals("John", user.name)
    assertEquals("a@a.com", user.email)
  }

  @Test
  fun lists() = runTest {
    val cacheManager = CacheManager(
        normalizedCacheFactory = MemoryCacheFactory(),
        cacheKeyGenerator = IdCacheKeyGenerator("id"),
        cacheResolver = IdCacheResolver(listOf("ids", "userIds")),
    )
    val apolloClient = ApolloClient.Builder().networkTransport(QueueTestNetworkTransport()).cacheManager(cacheManager).build()
    val query1 = GetUsersQuery(listOf("42", "43"))
    apolloClient.enqueueTestResponse(
        query1,
        GetUsersQuery.Data(
            listOf(
                GetUsersQuery.User(__typename = "User", id = "42", name = "John", email = "a@a.com"),
                GetUsersQuery.User(__typename = "User", id = "43", name = "Jane", email = "b@b.com"),
            )
        )
    )
    apolloClient.query(query1).fetchPolicy(FetchPolicy.NetworkOnly).execute()
    val users1 = apolloClient.query(query1).fetchPolicy(FetchPolicy.CacheOnly).execute().dataOrThrow().users
    assertEquals(2, users1.size)
    assertEquals("John", users1[0].name)
    assertEquals("a@a.com", users1[0].email)
    assertEquals("Jane", users1[1].name)
    assertEquals("b@b.com", users1[1].email)

    val query2 = GetUsersByIDsQuery(listOf("42", "43"))
    val users2 = apolloClient.query(query2).fetchPolicy(FetchPolicy.CacheOnly).execute().dataOrThrow().usersByIDs
    assertEquals(2, users2.size)
    assertEquals("John", users2[0].name)
    assertEquals("a@a.com", users2[0].email)
    assertEquals("Jane", users2[1].name)
    assertEquals("b@b.com", users2[1].email)
  }
}
