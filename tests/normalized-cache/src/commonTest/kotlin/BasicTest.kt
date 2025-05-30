package test

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.ApolloResponse
import com.apollographql.apollo.api.Query
import com.apollographql.cache.normalized.CacheManager
import com.apollographql.cache.normalized.FetchPolicy
import com.apollographql.cache.normalized.api.IdCacheKeyGenerator
import com.apollographql.cache.normalized.cacheManager
import com.apollographql.cache.normalized.fetchPolicy
import com.apollographql.cache.normalized.memory.MemoryCacheFactory
import com.apollographql.cache.normalized.testing.runTest
import com.apollographql.mockserver.MockServer
import com.apollographql.mockserver.enqueueString
import fixtures.AllPlanetsNullableField
import fixtures.HeroAndFriendsNameResponse
import fixtures.HeroAndFriendsNameWithIdsParentOnlyResponse
import fixtures.HeroAndFriendsNameWithIdsResponse
import fixtures.HeroAppearsInResponse
import fixtures.HeroAppearsInResponseWithNulls
import fixtures.HeroNameResponse
import fixtures.SameHeroTwiceResponse
import fixtures.StarshipByIdResponse
import httpcache.AllPlanetsQuery
import normalizer.EpisodeHeroNameQuery
import normalizer.HeroAndFriendsNamesQuery
import normalizer.HeroAndFriendsNamesWithIDForParentOnlyQuery
import normalizer.HeroAndFriendsNamesWithIDsQuery
import normalizer.HeroAppearsInQuery
import normalizer.SameHeroTwiceQuery
import normalizer.StarshipByIdQuery
import normalizer.type.Episode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * A series of high level cache tests that use NetworkOnly to cache a json and then retrieve it with CacheOnly and make
 * sure everything works.
 *
 * The tests are simple and are most likely already covered by the other tests but it's kept here for consistency
 * and maybe they'll catch something one day?
 */
class BasicTest {
  private lateinit var mockServer: MockServer
  private lateinit var apolloClient: ApolloClient
  private lateinit var cacheManager: CacheManager

  private suspend fun setUp() {
    cacheManager = CacheManager(
        normalizedCacheFactory = MemoryCacheFactory(),
        cacheKeyGenerator = IdCacheKeyGenerator()
    )
    mockServer = MockServer()
    apolloClient = ApolloClient.Builder().serverUrl(mockServer.url()).cacheManager(cacheManager).build()
  }

  private suspend fun tearDown() {
    mockServer.close()
  }

  private fun <D : Query.Data> basicTest(
      jsonPayload: String,
      query: Query<D>,
      block: ApolloResponse<D>.() -> Unit,
  ) = runTest(before = { setUp() }, after = { tearDown() }) {
    mockServer.enqueueString(jsonPayload)
    var response = apolloClient.query(query)
        .fetchPolicy(FetchPolicy.NetworkOnly)
        .execute()
    response.block()
    response = apolloClient.query(query)
        .fetchPolicy(FetchPolicy.CacheOnly)
        .execute()
    response.block()
  }

  @Test
  fun episodeHeroName() = basicTest(
      HeroNameResponse,
      EpisodeHeroNameQuery(Episode.EMPIRE)
  ) {

    assertFalse(hasErrors())
    assertEquals(data?.hero?.name, "R2-D2")
  }

  @Test
  @Throws(Exception::class)
  fun heroAndFriendsNameResponse() = basicTest(
      HeroAndFriendsNameResponse,
      HeroAndFriendsNamesQuery(Episode.JEDI)
  ) {

    assertFalse(hasErrors())
    assertEquals(data?.hero?.name, "R2-D2")
    assertEquals(data?.hero?.friends?.size, 3)
    assertEquals(data?.hero?.friends?.get(0)?.name, "Luke Skywalker")
    assertEquals(data?.hero?.friends?.get(1)?.name, "Han Solo")
    assertEquals(data?.hero?.friends?.get(2)?.name, "Leia Organa")
  }

  @Test
  fun heroAndFriendsNamesWithIDs() = basicTest(
      HeroAndFriendsNameWithIdsResponse,
      HeroAndFriendsNamesWithIDsQuery(Episode.NEWHOPE)
  ) {

    assertFalse(hasErrors())
    assertEquals(data?.hero?.id, "2001")
    assertEquals(data?.hero?.name, "R2-D2")
    assertEquals(data?.hero?.friends?.size, 3)
    assertEquals(data?.hero?.friends?.get(0)?.id, "1000")
    assertEquals(data?.hero?.friends?.get(0)?.name, "Luke Skywalker")
    assertEquals(data?.hero?.friends?.get(1)?.id, "1002")
    assertEquals(data?.hero?.friends?.get(1)?.name, "Han Solo")
    assertEquals(data?.hero?.friends?.get(2)?.id, "1003")
    assertEquals(data?.hero?.friends?.get(2)?.name, "Leia Organa")
  }

  @Test
  @Throws(Exception::class)
  fun heroAndFriendsNameWithIdsForParentOnly() = basicTest(
      HeroAndFriendsNameWithIdsParentOnlyResponse,
      HeroAndFriendsNamesWithIDForParentOnlyQuery(Episode.NEWHOPE)
  ) {

    assertFalse(hasErrors())
    assertEquals(data?.hero?.id, "2001")
    assertEquals(data?.hero?.name, "R2-D2")
    assertEquals(data?.hero?.friends?.size, 3)
    assertEquals(data?.hero?.friends?.get(0)?.name, "Luke Skywalker")
    assertEquals(data?.hero?.friends?.get(1)?.name, "Han Solo")
    assertEquals(data?.hero?.friends?.get(2)?.name, "Leia Organa")
  }

  @Test
  @Throws(Exception::class)
  fun heroAppearsInResponse() = basicTest(
      HeroAppearsInResponse,
      HeroAppearsInQuery()
  ) {

    assertFalse(hasErrors())
    assertEquals(data?.hero?.appearsIn?.size, 3)
    assertEquals(data?.hero?.appearsIn?.get(0), Episode.NEWHOPE)
    assertEquals(data?.hero?.appearsIn?.get(1), Episode.EMPIRE)
    assertEquals(data?.hero?.appearsIn?.get(2), Episode.JEDI)
  }

  @Test
  fun heroAppearsInResponseWithNulls() = basicTest(
      HeroAppearsInResponseWithNulls,
      HeroAppearsInQuery()
  ) {

    assertFalse(hasErrors())
    assertEquals(data?.hero?.appearsIn?.size, 6)
    assertNull(data?.hero?.appearsIn?.get(0))
    assertEquals(data?.hero?.appearsIn?.get(1), Episode.NEWHOPE)
    assertEquals(data?.hero?.appearsIn?.get(2), Episode.EMPIRE)
    assertNull(data?.hero?.appearsIn?.get(3))
    assertEquals(data?.hero?.appearsIn?.get(4), Episode.JEDI)
    assertNull(data?.hero?.appearsIn?.get(5))
  }


  @Test
  fun requestingTheSameFieldTwiceWithAnAlias() = basicTest(
      SameHeroTwiceResponse,
      SameHeroTwiceQuery()
  ) {
    assertFalse(hasErrors())
    assertEquals(data?.hero?.name, "R2-D2")
    assertEquals(data?.r2?.appearsIn?.size, 3)
    assertEquals(data?.r2?.appearsIn?.get(0), Episode.NEWHOPE)
    assertEquals(data?.r2?.appearsIn?.get(1), Episode.EMPIRE)
    assertEquals(data?.r2?.appearsIn?.get(2), Episode.JEDI)
  }

  @Test
  fun cacheResponseWithNullableFields() = basicTest(
      AllPlanetsNullableField,
      AllPlanetsQuery()
  ) {
    assertFalse(hasErrors())
  }

  @Test
  fun readList() = basicTest(
      HeroAndFriendsNameWithIdsResponse,
      HeroAndFriendsNamesWithIDsQuery(Episode.NEWHOPE)
  ) {
    assertEquals(data?.hero?.id, "2001")
    assertEquals(data?.hero?.name, "R2-D2")
    assertEquals(data?.hero?.friends?.size, 3)
    assertEquals(data?.hero?.friends?.get(0)?.id, "1000")
    assertEquals(data?.hero?.friends?.get(0)?.name, "Luke Skywalker")
    assertEquals(data?.hero?.friends?.get(1)?.id, "1002")
    assertEquals(data?.hero?.friends?.get(1)?.name, "Han Solo")
    assertEquals(data?.hero?.friends?.get(2)?.id, "1003")
    assertEquals(data?.hero?.friends?.get(2)?.name, "Leia Organa")
  }

  @Test
  fun listOfList() = basicTest(
      StarshipByIdResponse,
      StarshipByIdQuery("Starship1")
  ) {
    assertEquals(data?.starship?.name, "SuperRocket")
    assertEquals(data?.starship?.coordinates,
        listOf(
            listOf(100.0, 200.0),
            listOf(300.0, 400.0),
            listOf(500.0, 600.0)
        )
    )
  }
}
