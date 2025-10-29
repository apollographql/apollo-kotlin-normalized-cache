package test

import com.apollographql.apollo.ApolloClient
import com.apollographql.cache.normalized.FetchPolicy
import com.apollographql.cache.normalized.api.DefaultCacheKeyGenerator
import com.apollographql.cache.normalized.api.DefaultCacheResolver
import com.apollographql.cache.normalized.fetchPolicy
import com.apollographql.cache.normalized.memory.MemoryCacheFactory
import com.apollographql.cache.normalized.normalizedCache
import com.apollographql.cache.normalized.testing.runTest
import com.apollographql.mockserver.MockServer
import com.apollographql.mockserver.enqueueString
import fixtures.EpisodeHeroNameResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import normalizer.EpisodeHeroNameQuery
import normalizer.type.Episode
import kotlin.test.Test

class CancelTest {
  private lateinit var mockServer: MockServer

  private fun setUp() {
    mockServer = MockServer()
  }

  private suspend fun tearDown() {
    mockServer.close()
  }

  @Test
  fun cancelFlow() = runTest(before = { setUp() }, after = { tearDown() }) {
    mockServer.enqueueString(EpisodeHeroNameResponse)
    val apolloClient = ApolloClient.Builder().serverUrl(mockServer.url()).build()

    val job = launch {
      delay(100)
      apolloClient.query(EpisodeHeroNameQuery(Episode.EMPIRE)).execute()
      error("The Flow should have been canceled before reaching that state")
    }
    job.cancel()
    job.join()
  }

  @Test
  fun canCancelQueryCacheAndNetwork() = runTest(before = { setUp() }, after = { tearDown() }) {
    mockServer.enqueueString(EpisodeHeroNameResponse, 500)
    val apolloClient = ApolloClient.Builder().serverUrl(mockServer.url()).normalizedCache(
        normalizedCacheFactory = MemoryCacheFactory(),
        cacheKeyGenerator = DefaultCacheKeyGenerator,
        cacheResolver = DefaultCacheResolver,
    ).build()

    val job = launch {
      apolloClient.query(EpisodeHeroNameQuery(Episode.EMPIRE)).fetchPolicy(FetchPolicy.CacheAndNetwork).toFlow().toList()
    }
    delay(100)
    job.cancel()
  }
}
