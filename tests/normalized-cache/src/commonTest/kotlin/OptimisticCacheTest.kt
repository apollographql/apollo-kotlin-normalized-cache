package test

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Optional
import com.apollographql.cache.normalized.CacheManager
import com.apollographql.cache.normalized.FetchPolicy
import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.api.IdCacheKeyGenerator
import com.apollographql.cache.normalized.cacheManager
import com.apollographql.cache.normalized.fetchPolicy
import com.apollographql.cache.normalized.memory.MemoryCacheFactory
import com.apollographql.cache.normalized.optimisticUpdates
import com.apollographql.cache.normalized.refetchPolicy
import com.apollographql.cache.normalized.testing.runTest
import com.apollographql.cache.normalized.watch
import com.apollographql.mockserver.MockServer
import com.apollographql.mockserver.enqueueString
import com.benasher44.uuid.uuid4
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import normalizer.HeroAndFriendsNamesQuery
import normalizer.HeroAndFriendsNamesWithIDsQuery
import normalizer.HeroNameWithIdQuery
import normalizer.ReviewsByEpisodeQuery
import normalizer.UpdateReviewMutation
import normalizer.fragment.HeroAndFriendsNamesFragment
import normalizer.fragment.HeroAndFriendsNamesFragmentImpl
import normalizer.type.ColorInput
import normalizer.type.Episode
import normalizer.type.ReviewInput
import kotlin.test.Test
import kotlin.test.assertEquals

class OptimisticCacheTest {
  private lateinit var mockServer: MockServer
  private lateinit var apolloClient: ApolloClient
  private lateinit var cacheManager: CacheManager

  private suspend fun setUp() {
    cacheManager = CacheManager(MemoryCacheFactory(), cacheKeyGenerator = IdCacheKeyGenerator())
    mockServer = MockServer()
    apolloClient = ApolloClient.Builder().serverUrl(mockServer.url()).cacheManager(cacheManager).build()
  }

  private suspend fun tearDown() {
    mockServer.close()
  }

  /**
   * Write the updates programmatically, make sure they are seen,
   * roll them back, make sure we're back to the initial state
   */
  @Test
  fun programmaticOptimisticUpdates() = runTest(before = { setUp() }, after = { tearDown() }) {
    val query = HeroAndFriendsNamesQuery(Episode.JEDI)

    mockServer.enqueueString(testFixtureToUtf8("HeroAndFriendsNameResponse.json"))
    apolloClient.query(query).fetchPolicy(FetchPolicy.NetworkOnly).execute()

    val mutationId = uuid4()
    val data = HeroAndFriendsNamesQuery.Data(HeroAndFriendsNamesQuery.Hero(
        "Droid",
        "R222-D222",
        listOf(
            HeroAndFriendsNamesQuery.Friend(
                "Human",
                "SuperMan"
            ),
            HeroAndFriendsNamesQuery.Friend(
                "Human",
                "Batman"
            )
        )
    )
    )
    cacheManager.writeOptimisticUpdates(
        operation = query,
        data = data,
        mutationId = mutationId,
    ).also {
      cacheManager.publish(it)
    }

    var response = apolloClient.query(query).fetchPolicy(FetchPolicy.CacheOnly).execute()

    assertEquals<Any?>("R222-D222", response.data?.hero?.name)
    assertEquals<Any?>(2, response.data?.hero?.friends?.size)
    assertEquals<Any?>("SuperMan", response.data?.hero?.friends?.get(0)?.name)
    assertEquals<Any?>("Batman", response.data?.hero?.friends?.get(1)?.name)

    cacheManager.rollbackOptimisticUpdates(mutationId)
    response = apolloClient.query(query).fetchPolicy(FetchPolicy.CacheOnly).execute()

    assertEquals<Any?>("R2-D2", response.data?.hero?.name)
    assertEquals<Any?>(3, response.data?.hero?.friends?.size)
    assertEquals<Any?>("Luke Skywalker", response.data?.hero?.friends?.get(0)?.name)
    assertEquals<Any?>("Han Solo", response.data?.hero?.friends?.get(1)?.name)
    assertEquals<Any?>("Leia Organa", response.data?.hero?.friends?.get(2)?.name)
  }

  /**
   * Write the updates programmatically, make sure they are seen,
   * roll them back, make sure we're back to the initial state
   */
  @Test
  fun programmaticOptimisticFragmentUpdates() = runTest(before = { setUp() }, after = { tearDown() }) {
    val query = HeroAndFriendsNamesQuery(Episode.JEDI)

    mockServer.enqueueString(testFixtureToUtf8("HeroAndFriendsNameResponse.json"))
    apolloClient.query(query).fetchPolicy(FetchPolicy.NetworkOnly).execute()

    val mutationId = uuid4()
    val data = HeroAndFriendsNamesFragment(
        "Droid",
        "R222-D222",
        listOf(
            HeroAndFriendsNamesFragment.Friend(
                "Human",
                "SuperMan"
            ),
            HeroAndFriendsNamesFragment.Friend(
                "Human",
                "Batman"
            )
        )
    )
    cacheManager.writeOptimisticUpdates(
        HeroAndFriendsNamesFragmentImpl(),
        mutationId = mutationId,
        cacheKey = CacheKey("""hero({"episode":"JEDI"})"""),
        data = data,
    ).also {
      cacheManager.publish(it)
    }

    var response = apolloClient.query(query).fetchPolicy(FetchPolicy.CacheOnly).execute()

    assertEquals<Any?>("R222-D222", response.data?.hero?.name)
    assertEquals<Any?>(2, response.data?.hero?.friends?.size)
    assertEquals<Any?>("SuperMan", response.data?.hero?.friends?.get(0)?.name)
    assertEquals<Any?>("Batman", response.data?.hero?.friends?.get(1)?.name)

    cacheManager.rollbackOptimisticUpdates(mutationId)
    response = apolloClient.query(query).fetchPolicy(FetchPolicy.CacheOnly).execute()

    assertEquals<Any?>("R2-D2", response.data?.hero?.name)
    assertEquals<Any?>(3, response.data?.hero?.friends?.size)
    assertEquals<Any?>("Luke Skywalker", response.data?.hero?.friends?.get(0)?.name)
    assertEquals<Any?>("Han Solo", response.data?.hero?.friends?.get(1)?.name)
    assertEquals<Any?>("Leia Organa", response.data?.hero?.friends?.get(2)?.name)
  }

  /**
   * A more complex scenario where we stack optimistic updates
   */
  @Test
  fun two_optimistic_two_rollback() = runTest(before = { setUp() }, after = { tearDown() }) {
    val query1 = HeroAndFriendsNamesWithIDsQuery(Episode.JEDI)
    val mutationId1 = uuid4()

    // execute query1 from the network
    mockServer.enqueueString(testFixtureToUtf8("HeroAndFriendsNameWithIdsResponse.json"))
    apolloClient.query(query1).fetchPolicy(FetchPolicy.NetworkOnly).execute()

    // now write some optimistic updates for query1
    val data1 = HeroAndFriendsNamesWithIDsQuery.Data(
        HeroAndFriendsNamesWithIDsQuery.Hero(
            "Droid",
            "2001",
            "R222-D222",
            listOf(
                HeroAndFriendsNamesWithIDsQuery.Friend(
                    "Human",
                    "1000",
                    "SuperMan"
                ),
                HeroAndFriendsNamesWithIDsQuery.Friend(
                    "Human",
                    "1003",
                    "Batman"
                )
            )
        )
    )
    cacheManager.writeOptimisticUpdates(
        operation = query1,
        data = data1,
        mutationId = mutationId1,
    ).also {
      cacheManager.publish(it)
    }

    // check if query1 see optimistic updates
    var response1 = apolloClient.query(query1).fetchPolicy(FetchPolicy.CacheOnly).execute()
    assertEquals<Any?>("2001", response1.data?.hero?.id)
    assertEquals<Any?>("R222-D222", response1.data?.hero?.name)
    assertEquals<Any?>(2, response1.data?.hero?.friends?.size)
    assertEquals<Any?>("1000", response1.data?.hero?.friends?.get(0)?.id)
    assertEquals<Any?>("SuperMan", response1.data?.hero?.friends?.get(0)?.name)
    assertEquals<Any?>("1003", response1.data?.hero?.friends?.get(1)?.id)
    assertEquals<Any?>("Batman", response1.data?.hero?.friends?.get(1)?.name)

    // execute query2
    val query2 = HeroNameWithIdQuery()
    val mutationId2 = uuid4()

    mockServer.enqueueString(testFixtureToUtf8("HeroNameWithIdResponse.json"))
    apolloClient.query(query2).execute()

    // write optimistic data2
    val data2 = HeroNameWithIdQuery.Data(HeroNameWithIdQuery.Hero(
        "Human",
        "1000",
        "Beast"
    )
    )
    cacheManager.writeOptimisticUpdates(
        operation = query2,
        data = data2,
        mutationId = mutationId2,
    ).also {
      cacheManager.publish(it)
    }

    // check if query1 sees data2
    response1 = apolloClient.query(query1).fetchPolicy(FetchPolicy.CacheOnly).execute()
    assertEquals<Any?>("2001", response1.data?.hero?.id)
    assertEquals<Any?>("R222-D222", response1.data?.hero?.name)
    assertEquals<Any?>(2, response1.data?.hero?.friends?.size)
    assertEquals<Any?>("1000", response1.data?.hero?.friends?.get(0)?.id)
    assertEquals<Any?>("Beast", response1.data?.hero?.friends?.get(0)?.name)
    assertEquals<Any?>("1003", response1.data?.hero?.friends?.get(1)?.id)
    assertEquals<Any?>("Batman", response1.data?.hero?.friends?.get(1)?.name)

    // check if query2 sees data2
    var response2 = apolloClient.query(query2).fetchPolicy(FetchPolicy.CacheOnly).execute()
    assertEquals<Any?>("1000", response2.data?.hero?.id)
    assertEquals<Any?>("Beast", response2.data?.hero?.name)

    // rollback data1
    cacheManager.rollbackOptimisticUpdates(mutationId1)

    // check if query2 sees the rollback
    response1 = apolloClient.query(query1).fetchPolicy(FetchPolicy.CacheOnly).execute()
    assertEquals<Any?>("2001", response1.data?.hero?.id)
    assertEquals<Any?>("R2-D2", response1.data?.hero?.name)
    assertEquals<Any?>(3, response1.data?.hero?.friends?.size)
    assertEquals<Any?>("1000", response1.data?.hero?.friends?.get(0)?.id)
    assertEquals<Any?>("Beast", response1.data?.hero?.friends?.get(0)?.name)
    assertEquals<Any?>("1002", response1.data?.hero?.friends?.get(1)?.id)
    assertEquals<Any?>("Han Solo", response1.data?.hero?.friends?.get(1)?.name)
    assertEquals<Any?>("1003", response1.data?.hero?.friends?.get(2)?.id)
    assertEquals<Any?>("Leia Organa", response1.data?.hero?.friends?.get(2)?.name)

    // check if query2 see the latest optimistic updates
    response2 = apolloClient.query(query2).fetchPolicy(FetchPolicy.CacheOnly).execute()
    assertEquals<Any?>("1000", response2.data?.hero?.id)
    assertEquals<Any?>("Beast", response2.data?.hero?.name)

    // rollback query2 optimistic updates
    cacheManager.rollbackOptimisticUpdates(mutationId2)

    // check if query2 see the latest optimistic updates
    response2 = apolloClient.query(query2).fetchPolicy(FetchPolicy.CacheOnly).execute()
    assertEquals<Any?>("1000", response2.data?.hero?.id)
    assertEquals<Any?>("SuperMan", response2.data?.hero?.name)
  }

  @Test
  fun mutation_and_query_watcher() = runTest(before = { setUp() }, after = { tearDown() }) {
    mockServer.enqueueString(testFixtureToUtf8("ReviewsEmpireEpisodeResponse.json"))
    val channel = Channel<ReviewsByEpisodeQuery.Data?>()
    val job = launch {
      apolloClient.query(ReviewsByEpisodeQuery(Episode.EMPIRE))
          .fetchPolicy(FetchPolicy.NetworkOnly)
          .refetchPolicy(FetchPolicy.CacheOnly)
          .watch()
          .collect {
            channel.send(it.data)
          }
    }

    var watcherData = channel.receive()

    // before mutation and optimistic updates
    assertEquals<Any?>(3, watcherData?.reviews?.size)
    assertEquals<Any?>("empireReview1", watcherData?.reviews?.get(0)?.id)
    assertEquals<Any?>(1, watcherData?.reviews?.get(0)?.stars)
    assertEquals<Any?>("Boring", watcherData?.reviews?.get(0)?.commentary)
    assertEquals<Any?>("empireReview2", watcherData?.reviews?.get(1)?.id)
    assertEquals<Any?>(2, watcherData?.reviews?.get(1)?.stars)
    assertEquals<Any?>("So-so", watcherData?.reviews?.get(1)?.commentary)
    assertEquals<Any?>("empireReview3", watcherData?.reviews?.get(2)?.id)
    assertEquals<Any?>(5, watcherData?.reviews?.get(2)?.stars)
    assertEquals<Any?>("Amazing", watcherData?.reviews?.get(2)?.commentary)

    /**
     * There is a small potential for a race condition here. The changedKeys event from the optimistic updates might
     * be received after the network response has been written and therefore the refetch will see the new data right ahead.
     *
     * To limit the occurence of this happening, we introduce a small delay in the network response here.
     */
    mockServer.enqueueString(testFixtureToUtf8("UpdateReviewResponse.json"), 100)
    val updateReviewMutation = UpdateReviewMutation(
        "empireReview2",
        ReviewInput(
            stars = 4,
            commentary = Optional.Present("Not Bad"),
            favoriteColor = ColorInput(
                red = Optional.Absent,
                green = Optional.Absent,
                blue = Optional.Absent
            )
        )
    )
    apolloClient.mutation(updateReviewMutation).optimisticUpdates(
        UpdateReviewMutation.Data(
            UpdateReviewMutation.UpdateReview(
                __typename = "Review",
                id = "empireReview2",
                stars = 5,
                commentary = "Great"
            )
        )
    ).execute()

    /**
     * optimistic updates
     */
    watcherData = channel.receive()
    assertEquals<Any?>(3, watcherData?.reviews?.size)
    assertEquals<Any?>("empireReview1", watcherData?.reviews?.get(0)?.id)
    assertEquals<Any?>(1, watcherData?.reviews?.get(0)?.stars)
    assertEquals<Any?>("Boring", watcherData?.reviews?.get(0)?.commentary)
    assertEquals<Any?>("empireReview2", watcherData?.reviews?.get(1)?.id)
    assertEquals<Any?>(5, watcherData?.reviews?.get(1)?.stars)
    assertEquals<Any?>("Great", watcherData?.reviews?.get(1)?.commentary)
    assertEquals<Any?>("empireReview3", watcherData?.reviews?.get(2)?.id)
    assertEquals<Any?>(5, watcherData?.reviews?.get(2)?.stars)
    assertEquals<Any?>("Amazing", watcherData?.reviews?.get(2)?.commentary)

    // after mutation with rolled back optimistic updates
    watcherData = channel.awaitElement()
    assertEquals<Any?>(3, watcherData?.reviews?.size)
    assertEquals<Any?>("empireReview1", watcherData?.reviews?.get(0)?.id)
    assertEquals<Any?>(1, watcherData?.reviews?.get(0)?.stars)
    assertEquals<Any?>("Boring", watcherData?.reviews?.get(0)?.commentary)
    assertEquals<Any?>("empireReview2", watcherData?.reviews?.get(1)?.id)
    assertEquals<Any?>(4, watcherData?.reviews?.get(1)?.stars)
    assertEquals<Any?>("Not Bad", watcherData?.reviews?.get(1)?.commentary)
    assertEquals<Any?>("empireReview3", watcherData?.reviews?.get(2)?.id)
    assertEquals<Any?>(5, watcherData?.reviews?.get(2)?.stars)
    assertEquals<Any?>("Amazing", watcherData?.reviews?.get(2)?.commentary)

    job.cancel()
  }

  @Test
  @Throws(Exception::class)
  fun two_optimistic_reverse_rollback_order() = runTest(before = { setUp() }, after = { tearDown() }) {
    val query1 = HeroAndFriendsNamesWithIDsQuery(Episode.JEDI)
    val mutationId1 = uuid4()
    val query2 = HeroNameWithIdQuery()
    val mutationId2 = uuid4()

    mockServer.enqueueString(testFixtureToUtf8("HeroAndFriendsNameWithIdsResponse.json"))
    apolloClient.query(query1).execute()

    mockServer.enqueueString(testFixtureToUtf8("HeroNameWithIdResponse.json"))
    apolloClient.query(query2).execute()

    val data1 = HeroAndFriendsNamesWithIDsQuery.Data(
        HeroAndFriendsNamesWithIDsQuery.Hero(
            "Droid",
            "2001",
            "R222-D222",
            listOf(
                HeroAndFriendsNamesWithIDsQuery.Friend(
                    "Human",
                    "1000",
                    "Robocop"
                ),
                HeroAndFriendsNamesWithIDsQuery.Friend(
                    "Human",
                    "1003",
                    "Batman"
                )
            )
        )
    )
    cacheManager.writeOptimisticUpdates(
        operation = query1,
        data = data1,
        mutationId = mutationId1,
    ).also {
      cacheManager.publish(it)
    }
    val data2 = HeroNameWithIdQuery.Data(HeroNameWithIdQuery.Hero(
        "Human",
        "1000",
        "Spiderman"
    )
    )
    cacheManager.writeOptimisticUpdates(
        operation = query2,
        data = data2,
        mutationId = mutationId2,
    ).also {
      cacheManager.publish(it)
    }

    // check if query1 see optimistic updates
    var response1 = apolloClient.query(query1).fetchPolicy(FetchPolicy.CacheOnly).execute()
    assertEquals<Any?>("2001", response1.data?.hero?.id)
    assertEquals<Any?>("R222-D222", response1.data?.hero?.name)
    assertEquals<Any?>(2, response1.data?.hero?.friends?.size)
    assertEquals<Any?>("1000", response1.data?.hero?.friends?.get(0)?.id)
    assertEquals<Any?>("Spiderman", response1.data?.hero?.friends?.get(0)?.name)
    assertEquals<Any?>("1003", response1.data?.hero?.friends?.get(1)?.id)
    assertEquals<Any?>("Batman", response1.data?.hero?.friends?.get(1)?.name)


    // check if query2 see the latest optimistic updates
    var response2 = apolloClient.query(query2).fetchPolicy(FetchPolicy.CacheOnly).execute()
    assertEquals<Any?>("1000", response2.data?.hero?.id)
    assertEquals<Any?>("Spiderman", response2.data?.hero?.name)

    // rollback query2 optimistic updates
    cacheManager.rollbackOptimisticUpdates(mutationId2)

    // check if query1 see the latest optimistic updates
    response1 = apolloClient.query(query1).fetchPolicy(FetchPolicy.CacheOnly).execute()
    assertEquals<Any?>("2001", response1.data?.hero?.id)
    assertEquals<Any?>("R222-D222", response1.data?.hero?.name)
    assertEquals<Any?>(2, response1.data?.hero?.friends?.size)
    assertEquals<Any?>("1000", response1.data?.hero?.friends?.get(0)?.id)
    assertEquals<Any?>("Robocop", response1.data?.hero?.friends?.get(0)?.name)
    assertEquals<Any?>("1003", response1.data?.hero?.friends?.get(1)?.id)
    assertEquals<Any?>("Batman", response1.data?.hero?.friends?.get(1)?.name)


    // check if query2 see the latest optimistic updates
    response2 = apolloClient.query(query2).fetchPolicy(FetchPolicy.CacheOnly).execute()
    assertEquals<Any?>("1000", response2.data?.hero?.id)
    assertEquals<Any?>("Robocop", response2.data?.hero?.name)

    // rollback query1 optimistic updates
    cacheManager.rollbackOptimisticUpdates(mutationId1)

    // check if query1 see the latest non-optimistic updates
    response1 = apolloClient.query(query1).fetchPolicy(FetchPolicy.CacheOnly).execute()
    assertEquals<Any?>("2001", response1.data?.hero?.id)
    assertEquals<Any?>("R2-D2", response1.data?.hero?.name)
    assertEquals<Any?>(3, response1.data?.hero?.friends?.size)
    assertEquals<Any?>("1000", response1.data?.hero?.friends?.get(0)?.id)
    assertEquals<Any?>("SuperMan", response1.data?.hero?.friends?.get(0)?.name)
    assertEquals<Any?>("1002", response1.data?.hero?.friends?.get(1)?.id)
    assertEquals<Any?>("Han Solo", response1.data?.hero?.friends?.get(1)?.name)
    assertEquals<Any?>("1003", response1.data?.hero?.friends?.get(2)?.id)
    assertEquals<Any?>("Leia Organa", response1.data?.hero?.friends?.get(2)?.name)


    // check if query2 see the latest non-optimistic updates
    response2 = apolloClient.query(query2).fetchPolicy(FetchPolicy.CacheOnly).execute()
    assertEquals<Any?>("1000", response2.data?.hero?.id)
    assertEquals<Any?>("SuperMan", response2.data?.hero?.name)
  }
}
