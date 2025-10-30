package test

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.composeJsonResponse
import com.apollographql.apollo.exception.CacheMissException
import com.apollographql.cache.normalized.CacheManager
import com.apollographql.cache.normalized.FetchPolicy
import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.api.IdCacheKeyGenerator
import com.apollographql.cache.normalized.api.IdCacheResolver
import com.apollographql.cache.normalized.cacheManager
import com.apollographql.cache.normalized.fetchPolicy
import com.apollographql.cache.normalized.memory.MemoryCacheFactory
import com.apollographql.cache.normalized.testing.keyToString
import com.apollographql.cache.normalized.testing.runTest
import com.apollographql.mockserver.MockServer
import com.apollographql.mockserver.enqueueString
import fixtures.HeroAndFriendsNameResponse
import fixtures.HeroAndFriendsNameWithIdsResponse
import fixtures.HeroNameResponse
import normalizer.CharacterDetailsQuery
import normalizer.CharacterNameByIdQuery
import normalizer.EpisodeHeroNameQuery
import normalizer.HeroAndFriendsDirectivesQuery
import normalizer.HeroAndFriendsNamesWithIDsQuery
import normalizer.InstantQuery
import normalizer.UpdateReviewWithoutVariableMutation
import normalizer.type.Episode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every other test that doesn't fit in the other files
 */
class OtherCacheTest {
  private lateinit var mockServer: MockServer
  private lateinit var apolloClient: ApolloClient
  private lateinit var cacheManager: CacheManager

  private suspend fun setUp() {
    cacheManager =
      CacheManager(MemoryCacheFactory(), cacheKeyGenerator = IdCacheKeyGenerator(keyScope = CacheKey.Scope.SERVICE), cacheResolver = IdCacheResolver(keyScope = CacheKey.Scope.SERVICE))
    mockServer = MockServer()
    apolloClient = ApolloClient.Builder().serverUrl(mockServer.url()).cacheManager(cacheManager).build()
  }

  private suspend fun tearDown() {
    mockServer.close()
  }

  @Test
  fun masterDetailSuccess() = runTest(before = { setUp() }, after = { tearDown() }) {
    // Store a query that contains all data
    mockServer.enqueueString(HeroAndFriendsNameWithIdsResponse)
    apolloClient.query(HeroAndFriendsNamesWithIDsQuery(Episode.NEWHOPE))
        .fetchPolicy(FetchPolicy.NetworkOnly)
        .execute()

    // Getting a subtree of that data should work
    val detailsResponse = apolloClient.query(CharacterNameByIdQuery("1002"))
        .fetchPolicy(FetchPolicy.CacheOnly)
        .execute()


    assertEquals(detailsResponse.data?.character!!.name, "Han Solo")
  }

  @Test
  @Throws(Exception::class)
  fun masterDetailFailIncomplete() = runTest(before = { setUp() }, after = { tearDown() }) {
    // Store a query that contains all data
    mockServer.enqueueString(HeroAndFriendsNameWithIdsResponse)
    apolloClient.query(HeroAndFriendsNamesWithIDsQuery(Episode.NEWHOPE))
        .fetchPolicy(FetchPolicy.NetworkOnly)
        .execute()

    // Some details are not present in the master query, we should get a cache miss
    val e = apolloClient.query(CharacterDetailsQuery("1002")).fetchPolicy(FetchPolicy.CacheOnly).execute().exception as CacheMissException
    assertTrue(e.message!!.contains("Object '${CacheKey("1002").keyToString()}' has no field named 'birthDate'"))
  }


  @Test
  fun cacheMissThrows() = runTest(before = { setUp() }, after = { tearDown() }) {
    val e = apolloClient.query(EpisodeHeroNameQuery(Episode.EMPIRE))
        .fetchPolicy(FetchPolicy.CacheOnly)
        .execute()
        .exception!!
    assertTrue(e.message!!.contains("Object '${CacheKey("QUERY_ROOT").keyToString()}' has no field named 'hero"))
  }

  @Test
  @Throws(Exception::class)
  fun skipIncludeDirective() = runTest(before = { setUp() }, after = { tearDown() }) {
    mockServer.enqueueString(HeroAndFriendsNameResponse)
    apolloClient.query(HeroAndFriendsDirectivesQuery(Episode.JEDI, true, false))
        .fetchPolicy(FetchPolicy.NetworkOnly)
        .execute()

    var response = apolloClient.query(
        HeroAndFriendsDirectivesQuery(Episode.JEDI, true, false)
    )
        .fetchPolicy(FetchPolicy.CacheOnly)
        .execute()

    assertEquals<Any?>("R2-D2", response.data?.hero?.name)
    assertEquals<Any?>(3, response.data?.hero?.friends?.size)
    assertEquals<Any?>("Luke Skywalker", response.data?.hero?.friends?.get(0)?.name)
    assertEquals<Any?>("Han Solo", response.data?.hero?.friends?.get(1)?.name)
    assertEquals<Any?>("Leia Organa", response.data?.hero?.friends?.get(2)?.name)

    response = apolloClient.query(
        HeroAndFriendsDirectivesQuery(Episode.JEDI, false, false)
    )
        .fetchPolicy(FetchPolicy.CacheOnly)
        .execute()

    assertNull(response.data?.hero?.name)
    assertEquals<Any?>(3, response.data?.hero?.friends?.size)
    assertEquals<Any?>("Luke Skywalker", response.data?.hero?.friends?.get(0)?.name)
    assertEquals<Any?>("Han Solo", response.data?.hero?.friends?.get(1)?.name)
    assertEquals<Any?>("Leia Organa", response.data?.hero?.friends?.get(2)?.name)

    response = apolloClient.query(
        HeroAndFriendsDirectivesQuery(Episode.JEDI, true, true)
    )
        .fetchPolicy(FetchPolicy.CacheOnly)
        .execute()

    assertEquals<Any?>("R2-D2", response.data?.hero?.name)
    assertNull(response.data?.hero?.friends)
  }


  @Test
  fun skipIncludeDirectiveUnsatisfiedCache() = runTest(before = { setUp() }, after = { tearDown() }) {
    // Store a response that doesn't contain friends
    mockServer.enqueueString(HeroNameResponse)
    apolloClient.query(HeroAndFriendsDirectivesQuery(Episode.JEDI, true, true))
        .fetchPolicy(FetchPolicy.NetworkOnly)
        .execute()


    // Get it from the cache, we should get the name but no friends
    val response = apolloClient.query(HeroAndFriendsDirectivesQuery(Episode.JEDI, true, true))
        .fetchPolicy(FetchPolicy.CacheOnly)
        .execute()

    assertEquals(response.data?.hero?.name, "R2-D2")
    assertEquals(response.data?.hero?.friends, null)

    // Now try to get the friends from the cache, it should fail
    val e = apolloClient.query(HeroAndFriendsDirectivesQuery(Episode.JEDI, true, false))
        .fetchPolicy(FetchPolicy.CacheOnly)
        .execute()
        .exception as CacheMissException

    assertTrue(e.message!!.contains("has no field named 'friends'"))
  }

  @Test
  fun withCompileTimeScalarAdapter() = runTest(before = { setUp() }, after = { tearDown() }) {
    val query = InstantQuery()
    // Store in the cache
    val instant = "now"
    val data = InstantQuery.Data(instant)
    mockServer.enqueueString(query.composeJsonResponse(data))
    apolloClient.query(query).execute()

    // Get from the cache
    val response = apolloClient.query(query)
        .fetchPolicy(FetchPolicy.CacheOnly)
        .execute()

    assertEquals(instant, response.data!!.instant)
  }

  @Test
  fun cacheFieldWithObjectValueArgument() = runTest(before = { setUp() }, after = { tearDown() }) {
    val mutation = UpdateReviewWithoutVariableMutation()
    val data = UpdateReviewWithoutVariableMutation.Data(
        UpdateReviewWithoutVariableMutation.UpdateReview(
            __typename = "Review",
            "0",
            5,
            "Great"
        )
    )
    mockServer.enqueueString(mutation.composeJsonResponse(data))
    apolloClient.mutation(mutation).execute()

    val storeData = cacheManager.readOperation(mutation).data
    assertEquals(data, storeData)
  }
}
