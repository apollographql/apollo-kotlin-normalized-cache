package test

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.ApolloRequest
import com.apollographql.apollo.api.ApolloResponse
import com.apollographql.apollo.api.Operation
import com.apollographql.apollo.exception.ApolloNetworkException
import com.apollographql.apollo.exception.CacheMissException
import com.apollographql.apollo.network.NetworkTransport
import com.apollographql.cache.normalized.FetchPolicy
import com.apollographql.cache.normalized.apolloStore
import com.apollographql.cache.normalized.api.IdCacheKeyGenerator
import com.apollographql.cache.normalized.api.IdCacheResolver
import com.apollographql.cache.normalized.fetchPolicy
import com.apollographql.cache.normalized.memory.MemoryCacheFactory
import com.apollographql.cache.normalized.normalizedCache
import com.apollographql.cache.normalized.optimisticUpdates
import com.apollographql.cache.normalized.testing.runTest
import com.apollographql.cache.normalized.watch
import com.apollographql.cache.normalized.writeToCacheAsynchronously
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import optimistic.GetAnimalQuery
import optimistic.UpdateAnimalNameMutation
import optimistic.type.AnimalInput
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How `watch()` reacts to cache changes made while its initial responses are being fetched, typically an optimistic mutation
 * started after the cache emission of [FetchPolicy.CacheAndNetwork] but before its network response. The scenarios about
 * the initial fetch run for each way it goes to the network: see [InitialFetch].
 *
 * The network is a [GatedNetworkTransport]: each request waits until the test responds to it, so the interleavings are
 * deterministic rather than timing based.
 */
class WatchInitialFetchTest {
  @Test
  fun cacheAndNetworkEmitsCacheThenNetworkThenLaterChanges() = test {
    watch(FetchPolicy.CacheAndNetwork).test {
      assertEquals("Noushka", awaitItem().name)
      transport.awaitRequest("GetAnimal").respond(animal("FromNetwork"))
      assertEquals("FromNetwork", awaitItem().name)

      // The watcher's own write of the network response doesn't trigger a refetch
      expectNoEventsAfterSettling()

      // A change made after the last initial response is observed
      apolloClient.apolloStore.writeOperation(GetAnimalQuery("1"), animal("Later"), publish = true)
      assertEquals("Later", awaitItem().name)
      expectNoEventsAfterSettling()
    }
  }

  @Test
  fun cacheAndNetworkWithAsynchronousWritesIgnoresItsOwnWrite() = test {
    watch(FetchPolicy.CacheAndNetwork, writeAsynchronously = true).test {
      assertEquals("Noushka", awaitItem().name)
      transport.awaitRequest("GetAnimal").respond(animal("FromNetwork"))
      assertEquals("FromNetwork", awaitItem().name)
      expectNoEventsAfterSettling()

      apolloClient.apolloStore.writeOperation(GetAnimalQuery("1"), animal("Later"), publish = true)
      assertEquals("Later", awaitItem().name)
    }
  }

  @Test
  fun optimisticMutationAfterInitialResponsesIsEmittedThenReplacedByItsResult() = test {
    watch(FetchPolicy.CacheAndNetwork).test {
      assertEquals("Noushka", awaitItem().name)
      transport.awaitRequest("GetAnimal").respond(animal("Noushka"))
      assertEquals("Noushka", awaitItem().name)

      launchMutation("Medor")
      val mutation = transport.awaitRequest("UpdateAnimalName")
      assertEquals("Medor", awaitItem().name)

      mutation.respond(mutationResult("ServerMedor"))
      assertEquals("ServerMedor", awaitItem().name)
      expectNoEventsAfterSettling()
    }
  }

  @Test
  fun optimisticMutationAfterInitialResponsesIsRolledBackOnFailure() = test {
    watch(FetchPolicy.CacheAndNetwork).test {
      assertEquals("Noushka", awaitItem().name)
      transport.awaitRequest("GetAnimal").respond(animal("Noushka"))
      assertEquals("Noushka", awaitItem().name)

      launchMutation("Medor")
      val mutation = transport.awaitRequest("UpdateAnimalName")
      assertEquals("Medor", awaitItem().name)

      mutation.fail()
      assertEquals("Noushka", awaitItem().name)
      expectNoEventsAfterSettling()
    }
  }

  @Test
  fun optimisticMutationDuringInitialFetchIsNotHiddenByTheNetworkResponse() = testEachInitialFetch { initialFetch ->
    watch(initialFetch.fetchPolicy).test {
      val network = awaitInitialNetworkRequest(initialFetch)

      launchMutation("Medor")
      val mutation = transport.awaitRequest("UpdateAnimalName")
      awaitOptimisticDataBeforeTheNetworkResponse(initialFetch)

      // Not the raw network response, which doesn't have the optimistic data
      network.respond(animal("Noushka"))
      assertEquals("Medor", awaitItem().name)
      expectNoEventsAfterSettling()

      mutation.respond(mutationResult("ServerMedor"))
      assertEquals("ServerMedor", awaitItem().name)
      expectNoEventsAfterSettling()
    }
  }

  @Test
  fun optimisticMutationDuringInitialFetchIsRolledBackOnFailure() = testEachInitialFetch { initialFetch ->
    watch(initialFetch.fetchPolicy).test {
      val network = awaitInitialNetworkRequest(initialFetch)

      launchMutation("Medor")
      val mutation = transport.awaitRequest("UpdateAnimalName")
      awaitOptimisticDataBeforeTheNetworkResponse(initialFetch)

      network.respond(animal("Noushka"))
      assertEquals("Medor", awaitItem().name)
      expectNoEventsAfterSettling()

      mutation.fail()
      assertEquals("Noushka", awaitItem().name)
      expectNoEventsAfterSettling()
    }
  }

  @Test
  fun optimisticMutationDuringInitialFetchIsNotHiddenWithAsynchronousWrites() = testEachInitialFetch { initialFetch ->
    watch(initialFetch.fetchPolicy, writeAsynchronously = true).test {
      val network = awaitInitialNetworkRequest(initialFetch)

      launchMutation("Medor")
      val mutation = transport.awaitRequest("UpdateAnimalName")
      awaitOptimisticDataBeforeTheNetworkResponse(initialFetch)

      // Whether or not the network response's write has landed when the cache is read back in its place. On an empty
      // cache, the optimistic data alone isn't enough until it has.
      network.respond(animal("Noushka"))
      assertEquals(listOf<String?>("Medor"), awaitSettled().map { it.name }.distinct())

      mutation.respond(mutationResult("ServerMedor"))
      assertEquals("ServerMedor", awaitSettled().last().name)
    }
  }

  @Test
  fun storeWriteDuringInitialFetchIsEmittedOnce() = testEachInitialFetch { initialFetch ->
    watch(initialFetch.fetchPolicy).test {
      val network = awaitInitialNetworkRequest(initialFetch)

      apolloClient.apolloStore.writeOperation(GetAnimalQuery("1"), animal("Written"), publish = true)
      if (initialFetch.emitsBeforeTheNetworkResponse) {
        // The watcher's network request is still pending
        assertEquals("Written", awaitItem().name)
      } else {
        expectNoEventsAfterSettling()
      }

      // The network response overwrites the write. Without the read back in its place, a refetch for the write would
      // emit the same data again.
      network.respond(animal("FromNetwork"))
      assertEquals("FromNetwork", awaitItem().name)
      expectNoEventsAfterSettling()
    }
  }

  @Test
  fun storeWriteDuringInitialFetchIsEmittedWhenTheNetworkFails() = testEachInitialFetch { initialFetch ->
    watch(initialFetch.fetchPolicy).test {
      val network = awaitInitialNetworkRequest(initialFetch)

      apolloClient.apolloStore.writeOperation(GetAnimalQuery("1"), animal("Written"), publish = true)
      network.fail()

      val responses = awaitSettled()
      // The network failure is emitted too
      assertTrue(responses.any { it.exception is ApolloNetworkException })
      assertEquals("Written", responses.last { it.data != null }.name)
    }
  }

  @Test
  fun unrelatedChangeDuringInitialFetchIsNotEmitted() = testEachInitialFetch { initialFetch ->
    watch(initialFetch.fetchPolicy).test {
      val network = awaitInitialNetworkRequest(initialFetch)

      apolloClient.apolloStore.writeOperation(GetAnimalQuery("2"), animal("Other", id = "2"), publish = true)
      expectNoEventsAfterSettling()

      network.respond(animal("FromNetwork"))
      assertEquals("FromNetwork", awaitItem().name)
      expectNoEventsAfterSettling()
    }
  }

  /**
   * The mutation completes while the watcher's network fetch is in flight, and that fetch was served by the server before
   * the mutation was applied. Its older data overwrites the mutation's result in the cache.
   *
   * Out of scope for the watcher: ordering responses that race on the network needs something like per-record versions.
   */
  @Ignore
  @Test
  fun staleInitialNetworkResponseDoesNotOverwriteACompletedMutation() = test {
    watch(FetchPolicy.CacheAndNetwork).test {
      assertEquals("Noushka", awaitItem().name)
      val network = transport.awaitRequest("GetAnimal")

      launchMutation("Medor")
      transport.awaitRequest("UpdateAnimalName").respond(mutationResult("ServerMedor"))
      network.respond(animal("Noushka"))

      awaitName("ServerMedor")
      expectNoEventsAfterSettling()
      assertEquals("ServerMedor", cachedName())
    }
  }

  private class Fixture(val scope: CoroutineScope) {
    val transport = GatedNetworkTransport()
    val apolloClient = ApolloClient.Builder()
        .networkTransport(transport)
        .normalizedCache(
            MemoryCacheFactory(),
            cacheKeyGenerator = IdCacheKeyGenerator(),
            cacheResolver = IdCacheResolver(),
            enableOptimisticUpdates = true,
        )
        .build()

    fun watch(fetchPolicy: FetchPolicy, writeAsynchronously: Boolean = false): Flow<ApolloResponse<GetAnimalQuery.Data>> {
      return apolloClient.query(GetAnimalQuery("1"))
          .fetchPolicy(fetchPolicy)
          .writeToCacheAsynchronously(writeAsynchronously)
          .watch()
    }

    fun launchMutation(optimisticName: String) {
      scope.launch {
        apolloClient.mutation(UpdateAnimalNameMutation(AnimalInput("1")))
            .optimisticUpdates(mutationResult(optimisticName))
            .execute()
      }
    }

    suspend fun cachedName(): String? {
      return apolloClient.query(GetAnimalQuery("1")).fetchPolicy(FetchPolicy.CacheOnly).execute().data?.animal?.name
    }

    /**
     * Awaits what [initialFetch] emits before going to the network, then its network request.
     */
    suspend fun ReceiveTurbine<ApolloResponse<GetAnimalQuery.Data>>.awaitInitialNetworkRequest(
        initialFetch: InitialFetch,
    ): GatedNetworkTransport.PendingRequest {
      when (initialFetch) {
        InitialFetch.CacheAndNetwork -> assertEquals("Noushka", awaitItem().name)
        InitialFetch.CacheMiss -> assertIs<CacheMissException>(awaitItem().exception)
        InitialFetch.NetworkOnly -> {}
      }
      return transport.awaitRequest("GetAnimal")
    }

    /**
     * Awaits the optimistic data while the watcher's network request is still pending, when [initialFetch] can show it.
     */
    suspend fun ReceiveTurbine<ApolloResponse<GetAnimalQuery.Data>>.awaitOptimisticDataBeforeTheNetworkResponse(
        initialFetch: InitialFetch,
    ) {
      if (initialFetch.emitsOptimisticDataBeforeTheNetworkResponse) {
        assertEquals("Medor", awaitItem().name)
      } else {
        // Nothing to show yet, not even another cache miss
        expectNoEventsAfterSettling()
      }
    }
  }

  /**
   * The ways a watcher's initial fetch goes to the network, which [testEachInitialFetch] runs a test for.
   */
  private enum class InitialFetch(
      val fetchPolicy: FetchPolicy,
      val seeded: Boolean,
      /**
       * Whether a change made while the network request is pending is emitted before its response.
       */
      val emitsBeforeTheNetworkResponse: Boolean,
      /**
       * Whether optimistic data is emitted before the network response, which also needs the cache to have the rest of
       * the query.
       */
      val emitsOptimisticDataBeforeTheNetworkResponse: Boolean = emitsBeforeTheNetworkResponse && seeded,
  ) {
    /**
     * Emits the cached data, then fetches.
     */
    CacheAndNetwork(FetchPolicy.CacheAndNetwork, seeded = true, emitsBeforeTheNetworkResponse = true),

    /**
     * [FetchPolicy.CacheFirst] on an empty cache: emits a cache miss, then fetches.
     */
    CacheMiss(FetchPolicy.CacheFirst, seeded = false, emitsBeforeTheNetworkResponse = true),

    /**
     * Emits nothing before the network response, so changes made during the fetch wait for it.
     */
    NetworkOnly(FetchPolicy.NetworkOnly, seeded = true, emitsBeforeTheNetworkResponse = false),
  }

  private fun test(seed: Boolean = true, block: suspend Fixture.() -> Unit) = runTest {
    withFixture(seed, block)
  }

  private fun testEachInitialFetch(block: suspend Fixture.(InitialFetch) -> Unit) = runTest {
    for (initialFetch in InitialFetch.entries) {
      try {
        withFixture(initialFetch.seeded) { block(initialFetch) }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        throw AssertionError("With $initialFetch: $e", e)
      }
    }
  }

  private suspend fun CoroutineScope.withFixture(seed: Boolean, block: suspend Fixture.() -> Unit) {
    val fixture = Fixture(this)
    if (seed) {
      fixture.apolloClient.apolloStore.writeOperation(GetAnimalQuery("1"), animal("Noushka"))
    }
    try {
      fixture.block()
    } finally {
      // A mutation whose request the test left pending
      coroutineContext.cancelChildren()
      fixture.apolloClient.close()
    }
  }
}

internal fun animal(name: String, id: String = "1") = GetAnimalQuery.Data(
    GetAnimalQuery.Animal(id = id, name = name, species = "Cat", __typename = "Cat"),
)

internal fun mutationResult(name: String) = UpdateAnimalNameMutation.Data(
    UpdateAnimalNameMutation.UpdateAnimal(
        success = true,
        animal = UpdateAnimalNameMutation.Animal(id = "1", name = name, __typename = "Cat"),
    ),
)

internal val ApolloResponse<GetAnimalQuery.Data>.name: String?
  get() = data?.animal?.name

/**
 * Awaits items until one has [name], for when intermediate emissions are allowed. Returns the names seen.
 */
internal suspend fun ReceiveTurbine<ApolloResponse<GetAnimalQuery.Data>>.awaitName(name: String): List<String?> {
  val seen = mutableListOf<String?>()
  while (seen.lastOrNull() != name || seen.isEmpty()) {
    seen += awaitItem().name
  }
  return seen
}

/**
 * Awaits at least one item, then every item until none comes for a while, for when only where the watcher settles matters.
 */
internal suspend fun <T> ReceiveTurbine<T>.awaitSettled(): List<T> {
  val items = mutableListOf(awaitItem())
  while (true) {
    // Not awaitItem(), which reports the cancellation of the timeout as a failure
    items += withTimeoutOrNull(300.milliseconds) { asChannel().receive() } ?: break
  }
  return items
}

/**
 * Lets in-flight work, such as a refetch the watcher shouldn't do, run before asserting that nothing was emitted.
 */
internal suspend fun ReceiveTurbine<*>.expectNoEventsAfterSettling() {
  delay(300)
  expectNoEvents()
}

/**
 * A [NetworkTransport] whose requests each wait until the test responds to them.
 */
internal class GatedNetworkTransport : NetworkTransport {
  private val requests = Channel<PendingRequest>(Channel.UNLIMITED)

  class PendingRequest(val request: ApolloRequest<*>) {
    val response = CompletableDeferred<ApolloResponse<*>>()

    @Suppress("UNCHECKED_CAST")
    fun <D : Operation.Data> respond(data: D) {
      response.complete(ApolloResponse.Builder(request.operation as Operation<D>, request.requestUuid).data(data).build())
    }

    fun fail() {
      response.complete(
          ApolloResponse.Builder(request.operation, request.requestUuid)
              .exception(ApolloNetworkException("Simulated network failure"))
              .build(),
      )
    }
  }

  override fun <D : Operation.Data> execute(request: ApolloRequest<D>): Flow<ApolloResponse<D>> = flow {
    val pending = PendingRequest(request)
    requests.send(pending)
    @Suppress("UNCHECKED_CAST")
    emit(pending.response.await() as ApolloResponse<D>)
  }

  suspend fun awaitRequest(operationName: String): PendingRequest {
    val pending = withTimeout(3.seconds) { requests.receive() }
    assertEquals(operationName, pending.request.operation.name())
    return pending
  }

  override fun dispose() {}
}
