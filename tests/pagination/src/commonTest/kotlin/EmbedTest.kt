package pagination

import com.apollographql.apollo.ApolloClient
import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.api.CacheKeyGenerator
import com.apollographql.cache.normalized.api.CacheKeyGeneratorContext
import com.apollographql.cache.normalized.api.DefaultEmbeddedFieldsProvider
import com.apollographql.cache.normalized.apolloStore
import com.apollographql.cache.normalized.internal.normalized
import com.apollographql.cache.normalized.memory.MemoryCacheFactory
import com.apollographql.cache.normalized.normalizedCache
import com.apollographql.cache.normalized.testing.runTest
import embed.GetHeroQuery
import embed.cache.Cache
import kotlin.test.Test
import kotlin.test.assertEquals

class EmbedTest {
  @Test
  fun normalize() {
    val query = GetHeroQuery()

    val records = GetHeroQuery.Data(
        GetHeroQuery.Hero("Hero",
            listOf(GetHeroQuery.Friend("Friend", "Luke", GetHeroQuery.Pet("Animal", "Snoopy")), GetHeroQuery.Friend("Friend", "Leia", GetHeroQuery.Pet("Animal", "Fluffy")))
        )
    ).normalized(
        query,
        cacheKeyGenerator = object : CacheKeyGenerator {
          override fun cacheKeyForObject(obj: Map<String, Any?>, context: CacheKeyGeneratorContext): CacheKey? {
            return null
          }
        },
        embeddedFieldsProvider = DefaultEmbeddedFieldsProvider(Cache.embeddedFields)
    )

    assertEquals(3, records.size)
  }

  @Test
  fun denormalize() = runTest {
    val client = ApolloClient.Builder()
        .normalizedCache(
            normalizedCacheFactory = MemoryCacheFactory(),
            embeddedFieldsProvider = DefaultEmbeddedFieldsProvider(Cache.embeddedFields)
        )
        .serverUrl("unused")
        .build()

    val query = GetHeroQuery()
    val data = GetHeroQuery.Data(
        GetHeroQuery.Hero("Hero",
            listOf(GetHeroQuery.Friend("Friend", "Luke", GetHeroQuery.Pet("Animal", "Snoopy")), GetHeroQuery.Friend("Friend", "Leia", GetHeroQuery.Pet("Animal", "Fluffy")))
        )
    )
    client.apolloStore.writeOperation(query, data)
    val dataFromStore = client.apolloStore.readOperation(query).data
    assertEquals(data, dataFromStore)
  }
}
