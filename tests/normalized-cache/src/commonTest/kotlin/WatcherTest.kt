package test

import app.cash.turbine.test
import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.ApolloRequest
import com.apollographql.apollo.api.ApolloResponse
import com.apollographql.apollo.api.Operation
import com.apollographql.apollo.api.composeJsonResponse
import com.apollographql.apollo.exception.ApolloNetworkException
import com.apollographql.apollo.exception.CacheMissException
import com.apollographql.apollo.interceptor.ApolloInterceptor
import com.apollographql.apollo.interceptor.ApolloInterceptorChain
import com.apollographql.apollo.testing.QueueTestNetworkTransport
import com.apollographql.apollo.testing.enqueueTestNetworkError
import com.apollographql.apollo.testing.enqueueTestResponse
import com.apollographql.cache.normalized.CacheManager
import com.apollographql.cache.normalized.FetchPolicy
import com.apollographql.cache.normalized.api.DefaultCacheKeyGenerator
import com.apollographql.cache.normalized.api.DefaultCacheResolver
import com.apollographql.cache.normalized.api.IdCacheKeyGenerator
import com.apollographql.cache.normalized.api.IdCacheResolver
import com.apollographql.cache.normalized.cacheManager
import com.apollographql.cache.normalized.fetchPolicy
import com.apollographql.cache.normalized.memory.MemoryCacheFactory
import com.apollographql.cache.normalized.normalizedCache
import com.apollographql.cache.normalized.refetchPolicy
import com.apollographql.cache.normalized.testing.runTest
import com.apollographql.cache.normalized.watch
import com.apollographql.mockserver.MockResponse
import com.apollographql.mockserver.MockServer
import com.apollographql.mockserver.enqueueString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import normalizer.EpisodeHeroNameQuery
import normalizer.EpisodeHeroNameWithIdQuery
import normalizer.HeroAndFriendsNamesWithIDsQuery
import normalizer.StarshipByIdQuery
import normalizer.type.Episode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

class WatcherTest {
  private lateinit var apolloClient: ApolloClient
  private lateinit var cacheManager: CacheManager

  private fun setUp() {
    cacheManager = CacheManager(MemoryCacheFactory(), cacheKeyGenerator = IdCacheKeyGenerator(), cacheResolver = IdCacheResolver())
    apolloClient = ApolloClient.Builder().networkTransport(QueueTestNetworkTransport()).cacheManager(cacheManager).build()
  }

  private val episodeHeroNameData = EpisodeHeroNameQuery.Data(EpisodeHeroNameQuery.Hero("Droid", "R2-D2"))
  private val episodeHeroNameChangedData = EpisodeHeroNameQuery.Data(EpisodeHeroNameQuery.Hero("Droid", "Artoo"))
  private val episodeHeroNameChangedTwoData = EpisodeHeroNameQuery.Data(EpisodeHeroNameQuery.Hero("Droid", "ArTwo"))

  private val episodeHeroNameWithIdData = EpisodeHeroNameWithIdQuery.Data(EpisodeHeroNameWithIdQuery.Hero("Droid", "2001", "R2-D2"))
  private val episodeHeroNameWithIdChangedData =
    EpisodeHeroNameWithIdQuery.Data(EpisodeHeroNameWithIdQuery.Hero("Droid", "2001", "ArTwo"))

  private val starshipByIdData = StarshipByIdQuery.Data(
      StarshipByIdQuery.Starship("Starship", "Starship1", "SuperRocket", listOf(listOf(900.0, 800.0)))
  )


  private val heroAndFriendsNamesWithIDsData = HeroAndFriendsNamesWithIDsQuery.Data(
      HeroAndFriendsNamesWithIDsQuery.Hero("Droid", "2001", "R2-D2", listOf(
          HeroAndFriendsNamesWithIDsQuery.Friend("Human", "1000", "Luke Skywalker"),
          HeroAndFriendsNamesWithIDsQuery.Friend("Human", "1002", "Han Solo"),
          HeroAndFriendsNamesWithIDsQuery.Friend("Human", "1003", "Leia Organa"),
      )
      )
  )
  private val heroAndFriendsNamesWithIDsNameChangedData = HeroAndFriendsNamesWithIDsQuery.Data(
      HeroAndFriendsNamesWithIDsQuery.Hero("Human", "1000", "Luke Skywalker", listOf(
          HeroAndFriendsNamesWithIDsQuery.Friend("Droid", "2001", "Artoo"),
          HeroAndFriendsNamesWithIDsQuery.Friend("Human", "1002", "Han Solo"),
          HeroAndFriendsNamesWithIDsQuery.Friend("Human", "1003", "Leia Organa"),
      )
      )
  )

  /**
   * Changes the name of `Human:1000`, which [heroAndFriendsNamesWithIDsData] has as a friend, and
   * touches nothing else it holds: the hero here is that same friend, and its own friend is an
   * entity of its own.
   */
  private val heroAndFriendsNamesWithIDsFriendNameChangedData = HeroAndFriendsNamesWithIDsQuery.Data(
      HeroAndFriendsNamesWithIDsQuery.Hero("Human", "1000", "Luke Starkiller", listOf(
          HeroAndFriendsNamesWithIDsQuery.Friend("Human", "1004", "Wilhuff Tarkin"),
      )
      )
  )

  private fun myRunTest(block: suspend CoroutineScope.() -> Unit) {
    kotlinx.coroutines.test.runTest(timeout = 10.minutes) {
      withContext(Dispatchers.Default.limitedParallelism(1)) {
        block()
      }
    }
  }

  /**
   * Executing the same query out of band should update the watcher
   *
   * Also, this test checks that the watcher gets control fast enough to subscribe to
   * cache changes
   */
  @Test
  fun sameQueryTriggersWatcher() = myRunTest {
    setUp()

    val query = EpisodeHeroNameQuery(Episode.EMPIRE)
    val channel = Channel<EpisodeHeroNameQuery.Data?>()

    repeat(10000) {
      // Enqueue responses
      apolloClient.enqueueTestResponse(query, episodeHeroNameData)
      apolloClient.enqueueTestResponse(query, episodeHeroNameChangedData)

      val job = launch(start = CoroutineStart.UNDISPATCHED) {
        val flow = apolloClient.query(query).fetchPolicy(FetchPolicy.NetworkOnly).watch()
        flow.collect {
          channel.send(it.data)
        }
      }

      assertEquals("R2-D2", channel.awaitElement()?.hero?.name)

      // Another newer call gets updated information with "Artoo"
      apolloClient.query(query).fetchPolicy(FetchPolicy.NetworkOnly).execute()

      assertEquals("Artoo", channel.awaitElement()?.hero?.name)

      job.cancel()
    }
  }

  @Test
  fun cacheMissesAreEmitted() = runTest(before = { setUp() }) {
    val query = EpisodeHeroNameQuery(Episode.EMPIRE)
    val channel = Channel<EpisodeHeroNameQuery.Data?>()

    apolloClient.enqueueTestResponse(query, episodeHeroNameData)

    val job = launch {
      apolloClient.query(query)
          .fetchPolicy(FetchPolicy.CacheOnly)
          .watch()
          .collect {
            channel.send(it.data)
          }
    }

    val data = channel.awaitElement()
    assertNull(data)

    // Update the cache
    apolloClient.query(query).fetchPolicy(FetchPolicy.NetworkOnly).execute()

    assertEquals("R2-D2", channel.awaitElement()?.hero?.name)

    job.cancel()
  }


  /**
   * Writing to the store out of band should update the watcher
   */
  @Test
  fun storeWriteTriggersWatcher() = runTest(before = { setUp() }) {
    val channel = Channel<EpisodeHeroNameWithIdQuery.Data?>()
    val operation = EpisodeHeroNameWithIdQuery(Episode.EMPIRE)
    apolloClient.enqueueTestResponse(operation, episodeHeroNameWithIdData)
    val job = launch {
      apolloClient.query(operation).watch().collect {
        channel.send(it.data)
      }
    }

    // Cache miss is emitted first (null data)
    assertNull(channel.awaitElement())
    assertEquals("R2-D2", channel.awaitElement()?.hero?.name)

    // Someone writes to the store directly
    val data = EpisodeHeroNameWithIdQuery.Data(
        EpisodeHeroNameWithIdQuery.Hero(
            "Droid",
            "2001",
            "Artoo"
        ),
    )

    cacheManager.writeOperation(operation, data, publish = true)

    assertEquals("Artoo", channel.awaitElement()?.hero?.name)

    job.cancel()
  }

  /**
   * A new query updates the store with data that is the same as the one originally seen by the watcher
   */
  @Test
  fun noChangeSameQuery() = runTest(before = { setUp() }) {
    val query = EpisodeHeroNameQuery(Episode.EMPIRE)
    val channel = Channel<EpisodeHeroNameQuery.Data?>()

    // The first query should get a "R2-D2" name
    apolloClient.enqueueTestResponse(query, episodeHeroNameData)
    val job = launch {
      apolloClient.query(query).watch().collect {
        channel.send(it.data)
      }
    }

    // Cache miss is emitted first (null data)
    assertNull(channel.awaitElement())
    assertEquals("R2-D2", channel.awaitElement()?.hero?.name)

    // Another newer call gets the same name (R2-D2)
    apolloClient.enqueueTestResponse(query, episodeHeroNameData)
    apolloClient.query(query).fetchPolicy(FetchPolicy.NetworkOnly).execute()

    channel.assertEmpty()

    job.cancel()
  }

  /**
   * A new query that contains overlapping fields with the watched query should trigger the watcher
   */
  @Test
  fun differentQueryTriggersWatcher() = runTest(before = { setUp() }) {
    val channel = Channel<EpisodeHeroNameWithIdQuery.Data?>()

    // The first query should get a "R2-D2" name
    val episodeHeroNameWithIdQuery = EpisodeHeroNameWithIdQuery(Episode.EMPIRE)
    apolloClient.enqueueTestResponse(episodeHeroNameWithIdQuery, episodeHeroNameWithIdData)
    val job = launch {
      apolloClient.query(episodeHeroNameWithIdQuery).watch().collect {
        channel.send(it.data)
      }
    }

    // Cache miss is emitted first (null data)
    assertNull(channel.awaitElement())
    assertEquals("R2-D2", channel.awaitElement()?.hero?.name)

    // Another newer call gets updated information with "Artoo"
    val heroAndFriendsNamesWithIDsQuery = HeroAndFriendsNamesWithIDsQuery(Episode.NEWHOPE)
    apolloClient.enqueueTestResponse(heroAndFriendsNamesWithIDsQuery, heroAndFriendsNamesWithIDsNameChangedData)
    apolloClient.query(heroAndFriendsNamesWithIDsQuery)
        .fetchPolicy(FetchPolicy.NetworkOnly)
        .execute()


    assertEquals("Artoo", channel.awaitElement()?.hero?.name)

    job.cancel()
  }

  @Test
  fun differentQueryTriggersWatcherAfterCleared() = runTest(before = { setUp() }) {
    val channel = Channel<EpisodeHeroNameWithIdQuery.Data?>()

    // The first query should get a "R2-D2" name
    val episodeHeroNameWithIdQuery = EpisodeHeroNameWithIdQuery(Episode.EMPIRE)
    apolloClient.enqueueTestResponse(episodeHeroNameWithIdQuery, episodeHeroNameWithIdData)
    val job = launch {
      apolloClient.query(episodeHeroNameWithIdQuery).watch().collect {
        channel.send(it.data)
      }
    }

    // Cache miss is emitted first (null data)
    assertNull(channel.awaitElement())
    assertEquals("R2-D2", channel.awaitElement()?.hero?.name)

    // Clear the cache
    cacheManager.clearAll().also { cacheManager.publish(CacheManager.ALL_KEYS) }
    // Cache miss due to the cache being cleared
    assertNull(channel.awaitElement())

    // Another newer call gets updated information with "Artoo"
    val heroAndFriendsNamesWithIDsQuery = HeroAndFriendsNamesWithIDsQuery(Episode.NEWHOPE)
    apolloClient.enqueueTestResponse(heroAndFriendsNamesWithIDsQuery, heroAndFriendsNamesWithIDsNameChangedData)
    apolloClient.query(heroAndFriendsNamesWithIDsQuery)
        .fetchPolicy(FetchPolicy.NetworkOnly)
        .execute()
    // Cache miss due to the cache being cleared
    assertNull(channel.awaitElement())

    job.cancel()
  }

  /**
   * Same as noChangeSameQuery with different queries
   */
  @Test
  fun noChangeDifferentQuery() = runTest(before = { setUp() }) {
    val channel = Channel<EpisodeHeroNameQuery.Data?>()

    // The first query should get a "R2-D2" name
    val episodeHeroNameQuery = EpisodeHeroNameQuery(Episode.EMPIRE)
    apolloClient.enqueueTestResponse(episodeHeroNameQuery, episodeHeroNameData)
    val job = launch {
      apolloClient.query(episodeHeroNameQuery).watch().collect {
        channel.send(it.data)
      }
    }

    // Cache miss is emitted first (null data)
    assertNull(channel.awaitElement())
    assertEquals("R2-D2", channel.receive()?.hero?.name)

    // Another newer call gets the same information
    val heroAndFriendsNamesWithIDsQuery = HeroAndFriendsNamesWithIDsQuery(Episode.NEWHOPE)
    apolloClient.enqueueTestResponse(heroAndFriendsNamesWithIDsQuery, heroAndFriendsNamesWithIDsData)
    apolloClient.query(heroAndFriendsNamesWithIDsQuery)
        .fetchPolicy(FetchPolicy.NetworkOnly)
        .execute()

    channel.assertEmpty()

    job.cancel()
  }

  /**
   * The keys a watcher matches cache changes against come from the latest response it has seen, and
   * they are only computed when a change actually needs them. Both of those have to keep holding
   * across a refetch: a watcher that loses its keys would match every subsequent change.
   */
  @Test
  fun watchedKeysStillFilterAfterARefetch() = runTest(before = { setUp() }) {
    val channel = Channel<EpisodeHeroNameWithIdQuery.Data?>()

    // The first query should get a "R2-D2" name
    val episodeHeroNameWithIdQuery = EpisodeHeroNameWithIdQuery(Episode.EMPIRE)
    apolloClient.enqueueTestResponse(episodeHeroNameWithIdQuery, episodeHeroNameWithIdData)
    val job = launch {
      apolloClient.query(episodeHeroNameWithIdQuery).watch().collect {
        channel.send(it.data)
      }
    }

    // Cache miss is emitted first (null data)
    assertNull(channel.awaitElement())
    assertEquals("R2-D2", channel.awaitElement()?.hero?.name)

    // An overlapping query triggers a refetch, which is where the watched keys are refreshed
    val heroAndFriendsNamesWithIDsQuery = HeroAndFriendsNamesWithIDsQuery(Episode.NEWHOPE)
    apolloClient.enqueueTestResponse(heroAndFriendsNamesWithIDsQuery, heroAndFriendsNamesWithIDsNameChangedData)
    apolloClient.query(heroAndFriendsNamesWithIDsQuery)
        .fetchPolicy(FetchPolicy.NetworkOnly)
        .execute()

    assertEquals("Artoo", channel.awaitElement()?.hero?.name)

    // A query sharing no key with the watched one is still filtered out after that refetch
    val starshipByIdQuery = StarshipByIdQuery("Starship1")
    apolloClient.enqueueTestResponse(starshipByIdQuery, starshipByIdData)
    apolloClient.query(starshipByIdQuery)
        .fetchPolicy(FetchPolicy.NetworkOnly)
        .execute()

    channel.assertEmpty()

    // ...while an overlapping one still reaches the watcher
    apolloClient.enqueueTestResponse(episodeHeroNameWithIdQuery, episodeHeroNameWithIdChangedData)
    apolloClient.query(episodeHeroNameWithIdQuery)
        .fetchPolicy(FetchPolicy.NetworkOnly)
        .execute()

    assertEquals("ArTwo", channel.awaitElement()?.hero?.name)

    job.cancel()
  }

  /**
   * A refetch served from the cache reports the keys it read, and the watcher uses those from then on.
   * They have to cover the whole response and not just its root: an object reached through a list is
   * as much part of what the watcher depends on as the root field is.
   */
  @Test
  fun watchedKeysFromACacheReadCoverListedObjects() = runTest(before = { setUp() }) {
    val channel = Channel<HeroAndFriendsNamesWithIDsQuery.Data?>()

    val heroAndFriendsNamesWithIDsQuery = HeroAndFriendsNamesWithIDsQuery(Episode.NEWHOPE)
    apolloClient.enqueueTestResponse(heroAndFriendsNamesWithIDsQuery, heroAndFriendsNamesWithIDsData)
    val job = launch {
      apolloClient.query(heroAndFriendsNamesWithIDsQuery).watch().collect {
        channel.send(it.data)
      }
    }

    // Cache miss is emitted first (null data)
    assertNull(channel.awaitElement())
    assertEquals("Luke Skywalker", channel.awaitElement()?.hero?.friends?.get(0)?.name)

    // Changing the hero triggers a refetch, which is served from the cache and so is where the
    // watched keys come from afterwards
    val episodeHeroNameWithIdQuery = EpisodeHeroNameWithIdQuery(Episode.EMPIRE)
    apolloClient.enqueueTestResponse(episodeHeroNameWithIdQuery, episodeHeroNameWithIdChangedData)
    apolloClient.query(episodeHeroNameWithIdQuery)
        .fetchPolicy(FetchPolicy.NetworkOnly)
        .execute()

    assertEquals("ArTwo", channel.awaitElement()?.hero?.name)

    // Changing one of the friends still reaches the watcher
    apolloClient.enqueueTestResponse(HeroAndFriendsNamesWithIDsQuery(Episode.EMPIRE), heroAndFriendsNamesWithIDsFriendNameChangedData)
    apolloClient.query(HeroAndFriendsNamesWithIDsQuery(Episode.EMPIRE))
        .fetchPolicy(FetchPolicy.NetworkOnly)
        .execute()

    assertEquals("Luke Starkiller", channel.awaitElement()?.hero?.friends?.get(0)?.name)

    job.cancel()
  }

  /**
   * The initial fetch writes its response to the cache and publishes the changed keys. The watcher
   * subscribes only once those responses are in, so it must not react to its own write.
   */
  @Test
  fun initialFetchDoesNotTriggerTheWatcher() = runTest(before = { setUp() }) {
    val query = EpisodeHeroNameWithIdQuery(Episode.EMPIRE)
    val channel = Channel<EpisodeHeroNameWithIdQuery.Data?>()

    // The cache starts empty, so the initial fetch does change it
    apolloClient.enqueueTestResponse(query, episodeHeroNameWithIdData)
    val job = launch {
      apolloClient.query(query).watch().collect {
        channel.send(it.data)
      }
    }

    // Cache miss is emitted first (null data)
    assertNull(channel.awaitElement())
    assertEquals("R2-D2", channel.awaitElement()?.hero?.name)

    channel.assertEmpty()

    job.cancel()
  }

  /**
   * The initial responses use the fetch policy while the refetches use the refetch policy, including
   * when the two disagree: a NetworkOnly watch still refetches from the cache under the default
   * CacheOnly refetch policy rather than going back to the network.
   */
  @Test
  fun refetchUsesTheRefetchPolicyRatherThanTheFetchPolicy() = runTest(before = { setUp() }) {
    val query = EpisodeHeroNameWithIdQuery(Episode.EMPIRE)
    val channel = Channel<EpisodeHeroNameWithIdQuery.Data?>()

    apolloClient.enqueueTestResponse(query, episodeHeroNameWithIdData)
    val job = launch {
      apolloClient.query(query)
          .fetchPolicy(FetchPolicy.NetworkOnly)
          .watch().collect {
            channel.send(it.data)
          }
    }

    assertEquals("R2-D2", channel.awaitElement()?.hero?.name)

    // Write "Artoo" out of band. Only one response was enqueued, so a refetch that went to the
    // network would fail instead of reading the cache.
    cacheManager.writeOperation(
        query,
        EpisodeHeroNameWithIdQuery.Data(EpisodeHeroNameWithIdQuery.Hero("Droid", "2001", "Artoo")),
        publish = true,
    )

    assertEquals("Artoo", channel.awaitElement()?.hero?.name)

    job.cancel()
  }

  /**
   * The initial responses and the cache subscription come from a single execution of the interceptor
   * chain, so interceptors sitting ahead of the cache run once rather than once per execution.
   */
  @Test
  fun watchExecutesTheInterceptorChainOnce() = runTest {
    var executions = 0
    val countingInterceptor = object : ApolloInterceptor {
      override fun <D : Operation.Data> intercept(
          request: ApolloRequest<D>,
          chain: ApolloInterceptorChain,
      ): Flow<ApolloResponse<D>> {
        executions++
        return chain.proceed(request)
      }
    }
    val cacheManager =
      CacheManager(MemoryCacheFactory(), cacheKeyGenerator = IdCacheKeyGenerator(), cacheResolver = IdCacheResolver())
    val apolloClient = ApolloClient.Builder()
        .networkTransport(QueueTestNetworkTransport())
        .cacheManager(cacheManager)
        .addInterceptor(countingInterceptor)
        .build()

    val query = EpisodeHeroNameWithIdQuery(Episode.EMPIRE)
    val channel = Channel<EpisodeHeroNameWithIdQuery.Data?>()
    apolloClient.enqueueTestResponse(query, episodeHeroNameWithIdData)
    val job = launch {
      apolloClient.query(query).watch().collect {
        channel.send(it.data)
      }
    }

    // Cache miss is emitted first (null data)
    assertNull(channel.awaitElement())
    assertEquals("R2-D2", channel.awaitElement()?.hero?.name)

    assertEquals(1, executions)

    job.cancel()
    apolloClient.close()
  }

  /**
   * A test to test refetching with a NetworkOnly refetchPolicy. On every change, the watcher should get new information
   * from the network
   */
  @Test
  fun networkRefetchPolicy() = runTest(before = { setUp() }) {
    val channel = Channel<EpisodeHeroNameQuery.Data?>()

    // The first query should get a "R2-D2" name
    val episodeHeroNameQuery = EpisodeHeroNameQuery(Episode.EMPIRE)
    apolloClient.enqueueTestResponse(episodeHeroNameQuery, episodeHeroNameData)
    val job = launch {
      apolloClient.query(episodeHeroNameQuery)
          .fetchPolicy(FetchPolicy.NetworkOnly)
          .refetchPolicy(FetchPolicy.NetworkOnly)
          .watch().collect {
            channel.send(it.data)
          }
    }

    assertEquals("R2-D2", channel.awaitElement()?.hero?.name)

    // Enqueue 2 responses.
    // - The first one will be for the query just below and contains "Artoo"
    // - The second one will be for the watcher refetch and contains "ArTwo"
    apolloClient.enqueueTestResponse(episodeHeroNameQuery, episodeHeroNameChangedData)
    apolloClient.enqueueTestResponse(episodeHeroNameQuery, episodeHeroNameChangedTwoData)
    // - Because the network only watcher will also store in the cache a different name value, it will trigger itself again
    // Enqueue a stable response to avoid errors during tests
    apolloClient.enqueueTestResponse(episodeHeroNameQuery, episodeHeroNameChangedTwoData)

    // Trigger a refetch
    val response = apolloClient.query(episodeHeroNameQuery)
        .fetchPolicy(FetchPolicy.NetworkOnly)
        .execute()
    assertEquals("Artoo", response.data?.hero?.name)

    // The watcher should refetch from the network and now see "ArTwo"
    assertEquals("ArTwo", channel.awaitElement()?.hero?.name)

    job.cancel()
  }

  /**
   * A test to test refetching with a CacheFirst refetchPolicy. On a cache miss during refetch,
   * the watcher should fall through to the network.
   */
  @Test
  fun cacheFirstRefetchPolicy() = runTest(before = { setUp() }) {
    val channel = Channel<ApolloResponse<EpisodeHeroNameQuery.Data>>(capacity = Channel.UNLIMITED)
    val query = EpisodeHeroNameQuery(Episode.EMPIRE)

    // Seed the watcher with an initial "R2-D2" response from the network
    apolloClient.enqueueTestResponse(query, episodeHeroNameData)
    val job = launch {
      apolloClient.query(query)
          .fetchPolicy(FetchPolicy.NetworkOnly)
          .refetchPolicy(FetchPolicy.CacheFirst)
          .watch()
          .collect {
            channel.send(it)
          }
    }
    assertEquals("R2-D2", channel.awaitElement().data?.hero?.name)

    // Clear the cache so the next refetch produces a cache miss.
    // With CacheFirst, the watcher is expected to fall through to the network.
    apolloClient.enqueueTestResponse(query, episodeHeroNameChangedData)
    cacheManager.clearAll()
    cacheManager.publish(CacheManager.ALL_KEYS)

    // Cache miss is emitted first (null data)
    assertIs<CacheMissException>(channel.awaitElement().exception)

    // The network fall-through brings back "Artoo"
    assertEquals("Artoo", channel.awaitElement().data?.hero?.name)

    job.cancel()
  }


  @Test
  fun nothingReceivedWhenCancelled() = runTest(before = { setUp() }) {
    val channel = Channel<EpisodeHeroNameQuery.Data?>()

    val query = EpisodeHeroNameQuery(Episode.EMPIRE)
    apolloClient.enqueueTestResponse(query, episodeHeroNameData)
    val job = launch {
      apolloClient.query(query)
          .fetchPolicy(FetchPolicy.NetworkOnly)
          .refetchPolicy(FetchPolicy.NetworkOnly)
          .watch()
          .collect {
            channel.send(it.data)
          }
    }
    job.cancelAndJoin()

    channel.assertEmpty()
  }

  /**
   * Doing the initial query as cache only will detect when the query becomes available
   */
  @Test
  fun cacheOnlyFetchPolicy() = runTest(before = { setUp() }) {
    val query = EpisodeHeroNameQuery(Episode.EMPIRE)
    val channel = Channel<EpisodeHeroNameQuery.Data?>()

    // This will initially miss as the cache should be empty
    val job = launch {
      apolloClient.query(query)
          .watch(null)
          .collect {
            channel.send(it.data)
          }
    }

    // Because subscribe is called from a background thread, give some time to be effective
    delay(500.milliseconds)

    // Another newer call gets updated information with "R2-D2"
    apolloClient.enqueueTestResponse(query, episodeHeroNameData)
    apolloClient.query(query).fetchPolicy(FetchPolicy.NetworkOnly).execute()

    assertEquals("R2-D2", channel.awaitElement()?.hero?.name)

    job.cancel()
  }

  @Test
  fun queryWatcherWithCacheOnlyNeverGoesToTheNetwork() = runTest(before = { setUp() }) {
    val channel = Channel<ApolloResponse<EpisodeHeroNameQuery.Data>>(capacity = Channel.UNLIMITED)
    val job = launch {

      apolloClient.query(EpisodeHeroNameQuery(Episode.EMPIRE))
          .fetchPolicy(FetchPolicy.CacheOnly)
          .refetchPolicy(FetchPolicy.CacheOnly)
          .watch().collect {
            channel.send(it)
          }
    }

    // execute a query that doesn't share any key with the main query
    // that will trigger a refetch that shouldn't throw
    apolloClient.query(StarshipByIdQuery("Starship1"))

    // Should see 1 cache miss values
    assertIs<CacheMissException>(channel.awaitElement().exception)
    channel.assertEmpty()

    job.cancel()
  }

  @Test
  fun watchCacheOrNetwork() = runTest(before = { setUp() }) {
    val channel = Channel<EpisodeHeroNameQuery.Data?>(capacity = Channel.UNLIMITED)
    val query = EpisodeHeroNameQuery(Episode.EMPIRE)

    // 1. get the result from the cache if any, if not, get it from the network
    // 2. observe new data

    // The first query should get a "R2-D2" name
    apolloClient.enqueueTestResponse(query, episodeHeroNameData)

    val job = launch {
      // 1. query (will be a cache miss since the cache starts empty), then watch
      apolloClient.query(query)
          .fetchPolicy(FetchPolicy.CacheFirst)
          .refetchPolicy(FetchPolicy.CacheOnly)
          .watch()
          .collect {
            channel.send(it.data)
          }
    }

    // Cache miss is emitted first (null data)
    assertNull(channel.awaitElement())
    assertEquals("R2-D2", channel.awaitElement()?.hero?.name)

    // Another newer call gets updated information with "Artoo"
    apolloClient.enqueueTestResponse(query, episodeHeroNameChangedData)
    apolloClient.query(query).fetchPolicy(FetchPolicy.NetworkOnly).execute()

    assertEquals("Artoo", channel.awaitElement()?.hero?.name)

    job.cancel()
  }

  @Test
  fun watchCacheAndNetworkManual() = runTest(before = { setUp() }) {
    val channel = Channel<EpisodeHeroNameQuery.Data?>(capacity = Channel.UNLIMITED)
    val query = EpisodeHeroNameQuery(Episode.EMPIRE)

    // 1. get the result from the cache if any
    // 2. get fresh data from the network
    // 3. observe new data

    // Set up the cache with a "R2-D2" name
    apolloClient.enqueueTestResponse(query, episodeHeroNameData)
    apolloClient.query(query).fetchPolicy(FetchPolicy.NetworkOnly).execute()

    // Prepare next call to get "Artoo"
    apolloClient.enqueueTestResponse(query, episodeHeroNameChangedData)

    val job = launch {
      apolloClient.query(query)
          .fetchPolicy(FetchPolicy.CacheAndNetwork)
          .refetchPolicy(FetchPolicy.CacheOnly)
          .watch()
          .collect {
            channel.send(it.data)
          }
    }
    // 1. Value from the cache
    assertEquals("R2-D2", channel.awaitElement()?.hero?.name)

    // 2. Value from the network
    assertEquals("Artoo", channel.awaitElement()?.hero?.name)

    // Another newer call updates the cache with "ArTwo"
    apolloClient.enqueueTestResponse(query, episodeHeroNameChangedTwoData)
    apolloClient.query(query).fetchPolicy(FetchPolicy.NetworkOnly).execute()

    // 3. Value from watching the cache
    assertEquals("ArTwo", channel.awaitElement()?.hero?.name)

    job.cancel()
  }

  /**
   * watchCacheAndNetwork() with cached value and no network error
   */
  @Test
  fun watchCacheAndNetwork() = runTest(before = { setUp() }) {
    val channel = Channel<EpisodeHeroNameQuery.Data?>(capacity = Channel.UNLIMITED)
    val query = EpisodeHeroNameQuery(Episode.EMPIRE)

    // Set up the cache with a "R2-D2" name
    apolloClient.enqueueTestResponse(query, episodeHeroNameData)
    apolloClient.query(query).fetchPolicy(FetchPolicy.NetworkOnly).execute()

    // Prepare next call to get "Artoo"
    apolloClient.enqueueTestResponse(query, episodeHeroNameChangedData)

    val job = launch {
      apolloClient.query(query).fetchPolicy(FetchPolicy.CacheAndNetwork).watch()
          .collect {
            channel.send(it.data)
          }
    }
    // 1. Value from the cache
    assertEquals("R2-D2", channel.awaitElement()?.hero?.name)

    // 2. Value from the network
    assertEquals("Artoo", channel.awaitElement(5000)?.hero?.name)

    // Another newer call updates the cache with "ArTwo"
    apolloClient.enqueueTestResponse(query, episodeHeroNameChangedTwoData)
    apolloClient.query(query).fetchPolicy(FetchPolicy.NetworkOnly).execute()

    // 3. Value from watching the cache
    assertEquals("ArTwo", channel.awaitElement()?.hero?.name)

    job.cancel()
  }

  /**
   * watchCacheAndNetwork() with a cache miss
   */
  @Test
  fun watchCacheAndNetworkWithCacheMiss() = runTest(before = { setUp() }) {
    val channel = Channel<EpisodeHeroNameQuery.Data?>(capacity = Channel.UNLIMITED)
    val query = EpisodeHeroNameQuery(Episode.EMPIRE)

    // Prepare next call to get "Artoo"
    apolloClient.enqueueTestResponse(query, episodeHeroNameChangedData)

    val job = launch {
      apolloClient.query(query).fetchPolicy(FetchPolicy.CacheAndNetwork).watch()
          .collect {
            channel.send(it.data)
          }
    }
    // 0. Cache miss (null data)
    assertNull(channel.awaitElement())
    // 1. Value from the network
    assertEquals("Artoo", channel.awaitElement(5000)?.hero?.name)

    // Another newer call updates the cache with "ArTwo"
    apolloClient.enqueueTestResponse(query, episodeHeroNameChangedTwoData)
    apolloClient.query(query).fetchPolicy(FetchPolicy.NetworkOnly).execute()

    // 2. Value from watching the cache
    assertEquals("ArTwo", channel.awaitElement()?.hero?.name)

    job.cancel()
  }

  @Test
  fun cacheAndNetworkEmitsCacheImmediately() = runTest {
    // This doesn't use TestNetworkTransport because we need timing control
    val mockServer = MockServer()
    val apolloClient = ApolloClient.Builder()
        .normalizedCache(MemoryCacheFactory(), cacheKeyGenerator = DefaultCacheKeyGenerator, cacheResolver = DefaultCacheResolver)
        .serverUrl(mockServer.url())
        .build()

    val query = EpisodeHeroNameQuery(Episode.EMPIRE)

    // Set up the cache with a "R2-D2" name
    mockServer.enqueueString(query.composeJsonResponse(episodeHeroNameData))
    apolloClient.query(query).fetchPolicy(FetchPolicy.NetworkOnly).execute()

    // Prepare next call to be a network error
    mockServer.enqueue(MockResponse.Builder().delayMillis(Long.MAX_VALUE).build())

    withTimeout(500.milliseconds) {
      // make sure we get the cache only result
      val response = apolloClient.query(query).fetchPolicy(FetchPolicy.CacheAndNetwork).watch().first()
      assertEquals("R2-D2", response.data?.hero?.name)
    }

    mockServer.close()
    apolloClient.close()
  }


  /**
   * watchCacheAndNetwork() with a network error on the initial call
   */
  @Test
  fun watchCacheAndNetworkWithNetworkError() = runTest(before = { setUp() }) {
    val channel = Channel<EpisodeHeroNameQuery.Data?>(capacity = Channel.UNLIMITED)
    val query = EpisodeHeroNameQuery(Episode.EMPIRE)

    // Set up the cache with a "R2-D2" name
    apolloClient.enqueueTestResponse(query, episodeHeroNameData)
    apolloClient.query(query).fetchPolicy(FetchPolicy.NetworkOnly).execute()

    // Prepare next call to be a network error
    apolloClient.enqueueTestNetworkError()

    val job = launch {
      apolloClient.query(query).fetchPolicy(FetchPolicy.CacheAndNetwork).watch()
          .collect {
            channel.send(it.data)
          }
    }
    // 1. Value from the cache
    assertEquals("R2-D2", channel.awaitElement()?.hero?.name)

    // 2. Exception from the network (null data)
    assertNull(channel.awaitElement())
    channel.assertEmpty()

    // Another newer call updates the cache with "ArTwo"
    apolloClient.enqueueTestResponse(query, episodeHeroNameChangedTwoData)
    apolloClient.query(query).fetchPolicy(FetchPolicy.NetworkOnly).execute()

    // 3. Value from watching the cache
    assertEquals("ArTwo", channel.awaitElement()?.hero?.name)

    job.cancel()
  }

  /**
   * watchCacheAndNetwork() with a cache error AND a network error on the initial call
   */
  @Test
  fun watchCacheAndNetworkWithCacheAndNetworkError() = runTest(before = { setUp() }) {
    val channel = Channel<ApolloResponse<EpisodeHeroNameQuery.Data>>(capacity = Channel.UNLIMITED)
    val query = EpisodeHeroNameQuery(Episode.EMPIRE)

    // Prepare next call to be a network error
    apolloClient.enqueueTestNetworkError()

    val job = launch {
      apolloClient.query(query).fetchPolicy(FetchPolicy.CacheAndNetwork).watch()
          .collect {
            channel.send(it)
          }
    }

    // We ge the cache miss and the network error
    assertIs<CacheMissException>(channel.awaitElement().exception)
    assertIs<ApolloNetworkException>(channel.awaitElement().exception)

    // Another newer call updates the cache with "ArTwo"
    apolloClient.enqueueTestResponse(query, episodeHeroNameChangedTwoData)
    apolloClient.query(query).fetchPolicy(FetchPolicy.NetworkOnly).execute()

    // Value from watching the cache
    assertEquals("ArTwo", channel.awaitElement().data?.hero?.name)

    job.cancel()
  }

  @Test
  fun publishAllKeys() = runTest(before = { setUp() }) {
    val query = EpisodeHeroNameQuery(Episode.EMPIRE)
    apolloClient.query(query)
        .fetchPolicy(FetchPolicy.CacheOnly)
        .watch()
        .test {
          // Start empty
          assertIs<CacheMissException>(awaitItem().exception)

          // Add data to the cache
          apolloClient.enqueueTestResponse(query, episodeHeroNameData)
          apolloClient.query(query).fetchPolicy(FetchPolicy.NetworkOnly).execute()
          assertEquals("R2-D2", awaitItem().data?.hero?.name)

          // Clear the cache
          cacheManager.clearAll()
          cacheManager.publish(CacheManager.ALL_KEYS)
          assertIs<CacheMissException>(awaitItem().exception)
        }
  }
}

internal suspend fun <T> Channel<T>.awaitElement(timeoutMillis: Long = 30000) = withTimeout(timeoutMillis.milliseconds) {
  receive()
}

internal suspend fun <T> Channel<T>.assertEmpty(timeoutMillis: Long = 300) {
  try {
    withTimeout(timeoutMillis.milliseconds) {
      receive()
    }
    error("An item was unexpectedly received")
  } catch (_: TimeoutCancellationException) {
    // nothing
  }
}
