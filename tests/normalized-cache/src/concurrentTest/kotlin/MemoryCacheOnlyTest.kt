import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.exception.CacheMissException
import com.apollographql.apollo.testing.QueueTestNetworkTransport
import com.apollographql.apollo.testing.enqueueTestResponse
import com.apollographql.cache.normalized.CacheManager
import com.apollographql.cache.normalized.FetchPolicy
import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.api.IdCacheKeyGenerator
import com.apollographql.cache.normalized.api.IdCacheKeyResolver
import com.apollographql.cache.normalized.api.Record
import com.apollographql.cache.normalized.cacheManager
import com.apollographql.cache.normalized.fetchPolicy
import com.apollographql.cache.normalized.memory.MemoryCache
import com.apollographql.cache.normalized.memory.MemoryCacheFactory
import com.apollographql.cache.normalized.memoryCacheOnly
import com.apollographql.cache.normalized.sql.SqlNormalizedCache
import com.apollographql.cache.normalized.testing.SqlNormalizedCacheFactory
import com.apollographql.cache.normalized.testing.runTest
import main.GetUserQuery
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MemoryCacheOnlyTest {
  @Test
  fun memoryCacheOnlyDoesNotStoreInSqlCache() = runTest {
    val cacheManager =
      CacheManager(MemoryCacheFactory().chain(SqlNormalizedCacheFactory()), cacheKeyGenerator = IdCacheKeyGenerator(), cacheResolver = IdCacheKeyResolver()).also { it.clearAll() }
    val apolloClient = ApolloClient.Builder().networkTransport(QueueTestNetworkTransport()).cacheManager(cacheManager).build()
    val query = GetUserQuery()
    apolloClient.enqueueTestResponse(query, GetUserQuery.Data(GetUserQuery.User(__typename = "User", "John", "a@a.com")))
    apolloClient.query(query).memoryCacheOnly(true).execute()
    val dump: Map<KClass<*>, Map<CacheKey, Record>> = cacheManager.dump()
    assertEquals(2, dump[MemoryCache::class]!!.size)
    assertEquals(0, dump[SqlNormalizedCache::class]!!.size)
  }

  @Test
  fun memoryCacheOnlyDoesNotReadFromSqlCache() = runTest {
    val cacheManager =
      CacheManager(MemoryCacheFactory().chain(SqlNormalizedCacheFactory()), cacheKeyGenerator = IdCacheKeyGenerator(), cacheResolver = IdCacheKeyResolver()).also { it.clearAll() }
    val query = GetUserQuery()
    cacheManager.writeOperation(query, GetUserQuery.Data(GetUserQuery.User(__typename = "User", "John", "a@a.com")))

    val store2 =
      CacheManager(MemoryCacheFactory().chain(SqlNormalizedCacheFactory()), cacheKeyGenerator = IdCacheKeyGenerator(), cacheResolver = IdCacheKeyResolver())
    val apolloClient = ApolloClient.Builder().serverUrl("unused").cacheManager(store2).build()
    // The record in is in the SQL cache, but we request not to access it
    assertIs<CacheMissException>(
        apolloClient.query(query).fetchPolicy(FetchPolicy.CacheOnly).memoryCacheOnly(true).execute().exception
    )
  }
}
