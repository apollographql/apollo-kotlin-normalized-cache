package test

import codegen.models.HeroParentTypeDependentFieldQuery
import codegen.models.MergedFieldWithSameShapeQuery
import codegen.models.type.Episode
import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.ApolloResponse
import com.apollographql.apollo.api.Optional
import com.apollographql.apollo.api.Query
import com.apollographql.cache.normalized.CacheManager
import com.apollographql.cache.normalized.FetchPolicy
import com.apollographql.cache.normalized.api.IdCacheKeyGenerator
import com.apollographql.cache.normalized.api.IdCacheKeyResolver
import com.apollographql.cache.normalized.cacheManager
import com.apollographql.cache.normalized.fetchPolicy
import com.apollographql.cache.normalized.memory.MemoryCacheFactory
import com.apollographql.cache.normalized.testing.runTest
import com.apollographql.mockserver.MockServer
import com.apollographql.mockserver.enqueueString
import fixtures.HeroParentTypeDependentField
import fixtures.MergedFieldWithSameShape_Droid
import fixtures.MergedFieldWithSameShape_Human
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BasicTest {
  private lateinit var mockServer: MockServer
  private lateinit var apolloClient: ApolloClient
  private lateinit var cacheManager: CacheManager

  private suspend fun setUp() {
    cacheManager = CacheManager(
        normalizedCacheFactory = MemoryCacheFactory(),
        cacheKeyGenerator = IdCacheKeyGenerator(),
        cacheResolver = IdCacheKeyResolver(),
    )
    mockServer = MockServer()
    apolloClient = ApolloClient.Builder().serverUrl(mockServer.url()).cacheManager(cacheManager).build()
  }

  private suspend fun tearDown() {
    mockServer.close()
  }

  private fun <D : Query.Data> basicTest(jsonPayload: String, query: Query<D>, block: ApolloResponse<D>.() -> Unit) =
    runTest(before = { setUp() }, after = { tearDown() }) {
      mockServer.enqueueString(jsonPayload)
      var response = apolloClient.query(query).fetchPolicy(FetchPolicy.NetworkOnly).execute()
      response.block()
      response = apolloClient.query(query).fetchPolicy(FetchPolicy.CacheOnly).execute()
      response.block()
    }

  @Test
  @Throws(Exception::class)
  fun heroParentTypeDependentField() = basicTest(
      HeroParentTypeDependentField,
      HeroParentTypeDependentFieldQuery(Optional.Present(Episode.NEWHOPE))
  ) {

    assertFalse(hasErrors())
    assertEquals(data?.hero?.name, "R2-D2")
    assertEquals(data?.hero?.name, "R2-D2")
    val hero = data?.hero?.onDroid!!
    assertEquals(hero.friends?.size, 3)
    assertEquals(hero.friends?.get(0)?.name, "Luke Skywalker")
    assertEquals(hero.friends?.get(0)?.name, "Luke Skywalker")
    assertEquals((hero.friends?.get(0)?.onHuman)?.height, 1.72)
  }


  @Test
  fun polymorphicDroidFieldsGetParsedToDroid() = basicTest(
      MergedFieldWithSameShape_Droid,
      MergedFieldWithSameShapeQuery(Optional.Present(Episode.NEWHOPE))
  ) {
    assertFalse(hasErrors())
    assertTrue(data?.hero?.onDroid != null)
    assertEquals(data?.hero?.onDroid?.property, "Astromech")
  }

  @Test
  fun polymorphicHumanFieldsGetParsedToHuman() = basicTest(
      MergedFieldWithSameShape_Human,
      MergedFieldWithSameShapeQuery(Optional.Present(Episode.NEWHOPE))
  ) {
    assertFalse(hasErrors())
    assertTrue(data?.hero?.onHuman != null)
    assertEquals(data?.hero?.onHuman?.property, "Tatooine")
  }
}
