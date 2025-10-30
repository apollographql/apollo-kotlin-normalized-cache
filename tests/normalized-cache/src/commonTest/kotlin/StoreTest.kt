package test

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.CustomScalarAdapters
import com.apollographql.apollo.api.StringAdapter
import com.apollographql.apollo.exception.CacheMissException
import com.apollographql.apollo.testing.QueueTestNetworkTransport
import com.apollographql.apollo.testing.enqueueTestResponse
import com.apollographql.cache.normalized.CacheManager
import com.apollographql.cache.normalized.FetchPolicy
import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.api.DefaultCacheKeyGenerator
import com.apollographql.cache.normalized.api.DefaultCacheResolver
import com.apollographql.cache.normalized.api.IdCacheKeyGenerator
import com.apollographql.cache.normalized.api.IdCacheResolver
import com.apollographql.cache.normalized.apolloStore
import com.apollographql.cache.normalized.cacheManager
import com.apollographql.cache.normalized.fetchPolicy
import com.apollographql.cache.normalized.isFromCache
import com.apollographql.cache.normalized.memory.MemoryCacheFactory
import com.apollographql.cache.normalized.normalizedCache
import com.apollographql.cache.normalized.removeFragment
import com.apollographql.cache.normalized.removeOperation
import com.apollographql.cache.normalized.testing.runTest
import normalizer.CharacterNameByIdQuery
import normalizer.ColorQuery
import normalizer.HeroAndFriendsNamesWithIDsQuery
import normalizer.HeroAndFriendsWithFragmentsQuery
import normalizer.fragment.HeroWithFriendsFragment
import normalizer.fragment.HeroWithFriendsFragmentImpl
import normalizer.fragment.HumanWithIdFragment
import normalizer.type.Color
import normalizer.type.Episode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Tests that write into the store programmatically.
 *
 * XXX: Do we need a client and mockServer for these tests?
 */
class StoreTest {
  private lateinit var apolloClient: ApolloClient
  private lateinit var cacheManager: CacheManager

  private fun setUp() {
    cacheManager =
      CacheManager(MemoryCacheFactory(), cacheKeyGenerator = IdCacheKeyGenerator(keyScope = CacheKey.Scope.SERVICE), cacheResolver = IdCacheResolver(keyScope = CacheKey.Scope.SERVICE))
    apolloClient = ApolloClient.Builder().networkTransport(QueueTestNetworkTransport()).cacheManager(cacheManager).build()
  }

  @Test
  fun removeFromStore() = runTest(before = { setUp() }) {
    storeAllFriends()
    assertFriendIsCached("1002", "Han Solo")

    // remove the root query object
    var removed = cacheManager.remove(CacheKey("2001"))
    assertEquals(true, removed)

    // Trying to get the full response should fail
    assertRootNotCached()

    // put everything in the cache
    storeAllFriends()
    assertFriendIsCached("1002", "Han Solo")

    // remove a single object from the list
    removed = cacheManager.remove(CacheKey("1002"))
    assertEquals(true, removed)

    // Trying to get the full response should fail
    assertRootNotCached()

    // Trying to get the object we just removed should fail
    assertFriendIsNotCached("1002")

    // Trying to get another object we did not remove should work
    assertFriendIsCached("1003", "Leia Organa")
  }

  @Test
  @Throws(Exception::class)
  fun removeMultipleFromStore() = runTest(before = { setUp() }) {
    storeAllFriends()
    assertFriendIsCached("1000", "Luke Skywalker")
    assertFriendIsCached("1002", "Han Solo")
    assertFriendIsCached("1003", "Leia Organa")

    // Now remove multiple keys
    val removed = cacheManager.remove(listOf(CacheKey("1002"), CacheKey("1000")))

    assertEquals(2, removed)

    // Trying to get the objects we just removed should fail
    assertFriendIsNotCached("1000")
    assertFriendIsNotCached("1002")
    assertFriendIsCached("1003", "Leia Organa")
  }

  @Test
  fun removeQueryFromStore() = runTest(before = { setUp() }) {
    // Setup the cache with ColorQuery and HeroAndFriendsNamesWithIDsQuery
    val colorQuery = ColorQuery()
    apolloClient.enqueueTestResponse(colorQuery, ColorQuery.Data(color = "red"))
    apolloClient.query(colorQuery).fetchPolicy(FetchPolicy.NetworkOnly).execute()
    storeAllFriends()

    // Remove fields from HeroAndFriendsNamesWithIDsQuery
    val operation = HeroAndFriendsNamesWithIDsQuery(Episode.NEWHOPE)
    val operationData = apolloClient.apolloStore.readOperation(operation).data!!
    apolloClient.apolloStore.removeOperation(operation, operationData)

    // Fields from HeroAndFriendsNamesWithIDsQuery should be removed
    assertFriendIsNotCached("1000")
    assertFriendIsNotCached("1002")
    assertFriendIsNotCached("1003")

    // But fields from ColorQuery should still be there
    val cacheResponse = apolloClient.query(colorQuery).fetchPolicy(FetchPolicy.CacheOnly).execute()
    assertEquals(cacheResponse.data?.color, "red")
  }

  @Test
  fun removeFragmentFromStore() = runTest(before = { setUp() }) {
    // Setup the cache with ColorQuery and HeroAndFriendsWithFragments
    val colorQuery = ColorQuery()
    apolloClient.enqueueTestResponse(colorQuery, ColorQuery.Data(color = "red"))
    apolloClient.query(colorQuery).fetchPolicy(FetchPolicy.NetworkOnly).execute()
    val heroAndFriendsWithFragmentsQuery = HeroAndFriendsWithFragmentsQuery()
    apolloClient.enqueueTestResponse(
        heroAndFriendsWithFragmentsQuery,
        HeroAndFriendsWithFragmentsQuery.Data(
            HeroAndFriendsWithFragmentsQuery.Hero(
                __typename = "Droid",
                heroWithFriendsFragment = HeroWithFriendsFragment(
                    "Droid",
                    id = "2001",
                    name = "R2-D2",
                    friends = listOf(
                        HeroWithFriendsFragment.Friend(
                            __typename = "Human",
                            humanWithIdFragment = HumanWithIdFragment(
                                __typename = "Human",
                                id = "1000",
                                name = "Luke Skywalker"
                            )
                        ),
                    )
                )
            )
        )
    )
    apolloClient.query(heroAndFriendsWithFragmentsQuery).fetchPolicy(FetchPolicy.NetworkOnly).execute()

    // Remove fields from HeroWithFriendsFragment
    val fragment = HeroWithFriendsFragmentImpl()
    val cacheKey = CacheKey("2001")
    val fragmentData = apolloClient.apolloStore.readFragment(
        fragment = fragment,
        cacheKey = cacheKey,
    ).data
    apolloClient.apolloStore.removeFragment(fragment, cacheKey, fragmentData)

    // Fields from HeroAndFriendsNamesWithIDsQuery should be removed
    assertFriendIsNotCached("2001")
    assertFriendIsNotCached("1000")

    // But fields from ColorQuery should still be there
    val cacheResponse = apolloClient.query(colorQuery).fetchPolicy(FetchPolicy.CacheOnly).execute()
    assertEquals(cacheResponse.data?.color, "red")
  }

  @Test
  @Throws(Exception::class)
  fun cascadeRemove() = runTest(before = { setUp() }) {
    // put everything in the cache
    storeAllFriends()

    assertFriendIsCached("1000", "Luke Skywalker")
    assertFriendIsCached("1002", "Han Solo")
    assertFriendIsCached("1003", "Leia Organa")

    // test remove root query object
    val removed = cacheManager.remove(CacheKey("2001"), true)
    assertEquals(true, removed)

    // Nothing should be cached anymore
    assertRootNotCached()
    assertFriendIsNotCached("1000")
    assertFriendIsNotCached("1002")
    assertFriendIsNotCached("1003")
  }

  @Test
  @Throws(Exception::class)
  fun directAccess() = runTest(before = { setUp() }) {
    // put everything in the cache
    storeAllFriends()

    cacheManager.accessCache {
      it.remove(CacheKey("1000"), false)
    }
    assertFriendIsNotCached("1000")
  }

  @Test
  fun testNewBuilderNewStore() = runTest(before = { setUp() }) {
    storeAllFriends()
    assertFriendIsCached("1000", "Luke Skywalker")

    val newCacheManager =
      CacheManager(MemoryCacheFactory(), cacheKeyGenerator = DefaultCacheKeyGenerator, cacheResolver = DefaultCacheResolver)
    val newClient = apolloClient.newBuilder().cacheManager(newCacheManager).build()

    assertFriendIsNotCached("1000", newClient)
  }

  private suspend fun storeAllFriends() {
    val query = HeroAndFriendsNamesWithIDsQuery(Episode.NEWHOPE)
    apolloClient.enqueueTestResponse(query, HeroAndFriendsNamesWithIDsQuery.Data(
        HeroAndFriendsNamesWithIDsQuery.Hero(
            "Droid",
            "2001",
            "R2-D2",
            listOf(
                HeroAndFriendsNamesWithIDsQuery.Friend(
                    "Human",
                    "1000",
                    "Luke Skywalker"
                ),
                HeroAndFriendsNamesWithIDsQuery.Friend(
                    "Human",
                    "1002",
                    "Han Solo"
                ),
                HeroAndFriendsNamesWithIDsQuery.Friend(
                    "Human",
                    "1003",
                    "Leia Organa"
                ),
            )
        )
    )
    )
    val response = apolloClient.query(query)
        .fetchPolicy(FetchPolicy.NetworkOnly).execute()

    assertEquals(response.data?.hero?.name, "R2-D2")
    assertEquals(response.data?.hero?.friends?.size, 3)
  }

  private suspend fun assertFriendIsCached(id: String, name: String) {
    val characterResponse = apolloClient.query(CharacterNameByIdQuery(id))
        .fetchPolicy(FetchPolicy.CacheOnly)
        .execute()

    assertEquals<Any?>(true, characterResponse.isFromCache)
    assertEquals<Any?>(name, characterResponse.data?.character?.name)
  }

  private suspend fun assertFriendIsNotCached(
      id: String,
      apolloClientToUse: ApolloClient = apolloClient,
  ) {
    assertIs<CacheMissException>(
        apolloClientToUse.query(CharacterNameByIdQuery(id))
            .fetchPolicy(FetchPolicy.CacheOnly)
            .execute()
            .exception
    )
  }

  private suspend fun assertRootNotCached() {
    assertIs<CacheMissException>(
        apolloClient.query(HeroAndFriendsNamesWithIDsQuery(Episode.NEWHOPE))
            .fetchPolicy(FetchPolicy.CacheOnly)
            .execute()
            .exception
    )
  }

  @Test
  fun customScalarAdapters() = runTest {
    val customScalarAdapters = CustomScalarAdapters.Builder()
        .add(Color.type, StringAdapter)
        .build()
    apolloClient = ApolloClient.Builder()
        .networkTransport(QueueTestNetworkTransport())
        .customScalarAdapters(customScalarAdapters)
        .normalizedCache(MemoryCacheFactory(), cacheKeyGenerator = IdCacheKeyGenerator(), cacheResolver = IdCacheResolver())
        .build()

    val query = ColorQuery()
    apolloClient.enqueueTestResponse(query, ColorQuery.Data(color = "red"))
    val networkResponse = apolloClient.query(query).fetchPolicy(FetchPolicy.NetworkOnly).execute()
    assertEquals(networkResponse.data?.color, "red")
    val cacheResponse = apolloClient.query(query).fetchPolicy(FetchPolicy.CacheOnly).execute()
    assertEquals(cacheResponse.data?.color, "red")
  }

}
