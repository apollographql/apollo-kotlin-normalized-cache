package test

import com.apollographql.apollo.ApolloClient
import com.apollographql.cache.normalized.CacheManager
import com.apollographql.cache.normalized.api.CacheResolver
import com.apollographql.cache.normalized.api.DefaultCacheResolver
import com.apollographql.cache.normalized.api.ResolverContext
import com.apollographql.cache.normalized.cacheManager
import com.apollographql.cache.normalized.memory.MemoryCacheFactory
import com.apollographql.cache.normalized.testing.runTest
import normalizer.HeroNameQuery
import kotlin.test.Test
import kotlin.test.assertEquals

class CacheResolverTest {
  @Test
  fun cacheResolverCanResolveQuery() = runTest {
    val resolver = object : CacheResolver {
      override fun resolveField(context: ResolverContext): Any? {
        return when (context.field.name) {
          "hero" -> mapOf("name" to "Luke", "__typename" to "Human")
          else -> DefaultCacheResolver.resolveField(context)
        }
      }
    }
    val apolloClient = ApolloClient.Builder().serverUrl(serverUrl = "")
        .cacheManager(
            CacheManager(
                normalizedCacheFactory = MemoryCacheFactory(),
                cacheResolver = resolver
            )
        )
        .build()

    val response = apolloClient.query(HeroNameQuery()).execute()

    assertEquals("Luke", response.data?.hero?.name)
  }
}
