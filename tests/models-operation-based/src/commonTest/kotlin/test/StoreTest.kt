package test

import codegen.models.HeroAndFriendsWithFragmentsQuery
import codegen.models.HeroAndFriendsWithTypenameQuery
import codegen.models.fragment.HeroWithFriendsFragment
import codegen.models.fragment.HeroWithFriendsFragmentImpl
import codegen.models.fragment.HumanWithIdFragment
import codegen.models.fragment.HumanWithIdFragmentImpl
import com.apollographql.apollo.ApolloClient
import com.apollographql.cache.normalized.CacheManager
import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.api.IdCacheKeyGenerator
import com.apollographql.cache.normalized.cacheManager
import com.apollographql.cache.normalized.memory.MemoryCacheFactory
import com.apollographql.cache.normalized.testing.runTest
import com.apollographql.mockserver.MockServer
import com.apollographql.mockserver.enqueueString
import testFixtureToUtf8
import kotlin.test.Test
import kotlin.test.assertEquals

class StoreTest {
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

  @Test
  fun readFragmentFromStore() = runTest(before = { setUp() }, after = { tearDown() }) {
    mockServer.enqueueString(testFixtureToUtf8("HeroAndFriendsWithTypename.json"))
    apolloClient.query(HeroAndFriendsWithTypenameQuery()).execute()

    val heroWithFriendsFragment = cacheManager.readFragment(
        HeroWithFriendsFragmentImpl(),
        CacheKey("Droid:2001"),
    ).data
    assertEquals(heroWithFriendsFragment.id, "2001")
    assertEquals(heroWithFriendsFragment.name, "R2-D2")
    assertEquals(heroWithFriendsFragment.friends?.size, 3)
    assertEquals(heroWithFriendsFragment.friends?.get(0)?.humanWithIdFragment?.id, "1000")
    assertEquals(heroWithFriendsFragment.friends?.get(0)?.humanWithIdFragment?.name, "Luke Skywalker")
    assertEquals(heroWithFriendsFragment.friends?.get(1)?.humanWithIdFragment?.id, "1002")
    assertEquals(heroWithFriendsFragment.friends?.get(1)?.humanWithIdFragment?.name, "Han Solo")
    assertEquals(heroWithFriendsFragment.friends?.get(2)?.humanWithIdFragment?.id, "1003")
    assertEquals(heroWithFriendsFragment.friends?.get(2)?.humanWithIdFragment?.name, "Leia Organa")

    var fragment = cacheManager.readFragment(
        HumanWithIdFragmentImpl(),
        CacheKey("Human:1000"),
    ).data

    assertEquals(fragment.id, "1000")
    assertEquals(fragment.name, "Luke Skywalker")

    fragment = cacheManager.readFragment(
        HumanWithIdFragmentImpl(),
        CacheKey("Human:1002"),
    ).data
    assertEquals(fragment.id, "1002")
    assertEquals(fragment.name, "Han Solo")

    fragment = cacheManager.readFragment(
        HumanWithIdFragmentImpl(),
        CacheKey("Human:1003"),
    ).data
    assertEquals(fragment.id, "1003")
    assertEquals(fragment.name, "Leia Organa")
  }

  /**
   * Modify the store by writing fragments
   */
  @Test
  fun fragments() = runTest(before = { setUp() }, after = { tearDown() }) {
    mockServer.enqueueString(testFixtureToUtf8("HeroAndFriendsNamesWithIDs.json"))
    val query = HeroAndFriendsWithFragmentsQuery()
    var response = apolloClient.query(query).execute()
    assertEquals(response.data?.hero?.__typename, "Droid")
    assertEquals(response.data?.hero?.heroWithFriendsFragment?.id, "2001")
    assertEquals(response.data?.hero?.heroWithFriendsFragment?.name, "R2-D2")
    assertEquals(response.data?.hero?.heroWithFriendsFragment?.friends?.size, 3)
    assertEquals(response.data?.hero?.heroWithFriendsFragment?.friends?.get(0)?.humanWithIdFragment?.id, "1000")
    assertEquals(response.data?.hero?.heroWithFriendsFragment?.friends?.get(0)?.humanWithIdFragment?.name, "Luke Skywalker")
    assertEquals(response.data?.hero?.heroWithFriendsFragment?.friends?.get(1)?.humanWithIdFragment?.id, "1002")
    assertEquals(response.data?.hero?.heroWithFriendsFragment?.friends?.get(1)?.humanWithIdFragment?.name, "Han Solo")
    assertEquals(response.data?.hero?.heroWithFriendsFragment?.friends?.get(2)?.humanWithIdFragment?.id, "1003")
    assertEquals(response.data?.hero?.heroWithFriendsFragment?.friends?.get(2)?.humanWithIdFragment?.name, "Leia Organa")

    cacheManager.writeFragment(
        HeroWithFriendsFragmentImpl(),
        CacheKey("Droid:2001"),
        HeroWithFriendsFragment(
            "2001",
            "R222-D222",
            listOf(
                HeroWithFriendsFragment.Friend(
                    "Human",
                    HumanWithIdFragment(
                        "1000",
                        "SuperMan"
                    )
                ),
                HeroWithFriendsFragment.Friend(
                    "Human",
                    HumanWithIdFragment(
                        "1002",
                        "Han Solo"
                    )
                ),
            )
        ),
    )

    cacheManager.writeFragment(
        HumanWithIdFragmentImpl(),
        CacheKey("Human:1002"),
        HumanWithIdFragment(
            "1002",
            "Beast"
        ),
    )

    // Values should have changed
    response = apolloClient.query(query).execute()
    assertEquals(response.data?.hero?.__typename, "Droid")
    assertEquals(response.data?.hero?.heroWithFriendsFragment?.id, "2001")
    assertEquals(response.data?.hero?.heroWithFriendsFragment?.name, "R222-D222")
    assertEquals(response.data?.hero?.heroWithFriendsFragment?.friends?.size, 2)
    assertEquals(response.data?.hero?.heroWithFriendsFragment?.friends?.get(0)?.humanWithIdFragment?.id, "1000")
    assertEquals(response.data?.hero?.heroWithFriendsFragment?.friends?.get(0)?.humanWithIdFragment?.name, "SuperMan")
    assertEquals(response.data?.hero?.heroWithFriendsFragment?.friends?.get(1)?.humanWithIdFragment?.id, "1002")
    assertEquals(response.data?.hero?.heroWithFriendsFragment?.friends?.get(1)?.humanWithIdFragment?.name, "Beast")
  }
}
