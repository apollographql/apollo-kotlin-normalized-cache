package test

import com.apollographql.apollo.ApolloClient
import com.apollographql.cache.normalized.FetchPolicy
import com.apollographql.cache.normalized.api.IdCacheKeyGenerator
import com.apollographql.cache.normalized.api.IdCacheResolver
import com.apollographql.cache.normalized.fetchPolicy
import com.apollographql.cache.normalized.memory.MemoryCacheFactory
import com.apollographql.cache.normalized.normalizedCache
import com.apollographql.cache.normalized.optimisticUpdates
import com.apollographql.cache.normalized.testing.runTest
import com.apollographql.mockserver.MockServer
import com.apollographql.mockserver.enqueueString
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import optimistic.GetAnimalQuery
import optimistic.UpdateAnimalNameMutation
import optimistic.type.AnimalInput
import kotlin.test.Test
import kotlin.test.assertEquals

class OptimisticCancellationTest {
  @Test
  fun cancelledMutationRollsBackOptimisticData() = runTest {
    val server = MockServer()
    val apolloClient = ApolloClient.Builder()
        .serverUrl(server.url())
        .normalizedCache(MemoryCacheFactory(), cacheKeyGenerator = IdCacheKeyGenerator(), cacheResolver = IdCacheResolver(), enableOptimisticUpdates = true)
        .build()

    server.enqueueString("""{"data":{"animal":{"__typename":"Cat","id":"1","name":"Noushka","species":"Cat"}}}""")
    apolloClient.query(GetAnimalQuery("1")).execute()

    server.enqueueString(
        """{"data":{"updateAnimal":{"success":true,"animal":{"__typename":"Cat","id":"1","name":"Medor"}}}}""",
        delayMs = 10_000,
    )
    val job = launch {
      apolloClient.mutation(UpdateAnimalNameMutation(AnimalInput("Irrelevant")))
          .optimisticUpdates(
              UpdateAnimalNameMutation.Data(
                  UpdateAnimalNameMutation.UpdateAnimal(
                      success = true,
                      animal = UpdateAnimalNameMutation.Animal(id = "1", __typename = "Cat", name = "Medor"),
                  )
              )
          )
          .execute()
    }
    delay(200)
    assertEquals("Medor", apolloClient.query(GetAnimalQuery("1")).fetchPolicy(FetchPolicy.CacheOnly).execute().data!!.animal!!.name)
    job.cancel()
    job.join()

    // A later server write should win over the abandoned optimistic value
    server.enqueueString("""{"data":{"animal":{"__typename":"Cat","id":"1","name":"FromServer","species":"Cat"}}}""")
    apolloClient.query(GetAnimalQuery("1")).fetchPolicy(FetchPolicy.NetworkOnly).execute()

    val cached = apolloClient.query(GetAnimalQuery("1")).fetchPolicy(FetchPolicy.CacheOnly).execute().data!!.animal!!
    assertEquals("FromServer", cached.name)

    apolloClient.close()
    server.close()
  }
}
