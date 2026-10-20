package test

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.ApolloRequest
import com.apollographql.apollo.api.ApolloResponse
import com.apollographql.apollo.api.Operation
import com.apollographql.apollo.exception.ApolloNetworkException
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import optimistic.GetAnimalQuery
import optimistic.UpdateAnimalNameMutation
import optimistic.type.AnimalInput
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * How `watch()` reacts to cache changes made while its initial responses are being fetched, typically an optimistic mutation
 * started after the cache emission of [FetchPolicy.CacheAndNetwork] but before its network response.
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
  fun unrelatedChangeDuringInitialFetchIsNotEmitted() = test {
    watch(FetchPolicy.CacheAndNetwork).test {
      assertEquals("Noushka", awaitItem().name)
      val network = transport.awaitRequest("GetAnimal")

      apolloClient.apolloStore.writeOperation(GetAnimalQuery("2"), animal("Other", id = "2"), publish = true)

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
  }

  private fun test(seed: Boolean = true, block: suspend Fixture.() -> Unit) = runTest {
    val fixture = Fixture(this)
    if (seed) {
      fixture.apolloClient.apolloStore.writeOperation(GetAnimalQuery("1"), animal("Noushka"))
    }
    try {
      fixture.block()
    } finally {
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
