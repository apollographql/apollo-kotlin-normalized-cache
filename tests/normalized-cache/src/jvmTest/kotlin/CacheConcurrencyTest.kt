package test

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.testing.QueueTestNetworkTransport
import com.apollographql.apollo.testing.enqueueTestResponse
import com.apollographql.cache.normalized.CacheManager
import com.apollographql.cache.normalized.cacheManager
import com.apollographql.cache.normalized.memory.MemoryCacheFactory
import com.apollographql.cache.normalized.testing.runTest
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import normalizer.CharacterNameByIdQuery
import java.util.concurrent.Executors
import kotlin.test.Test

class CacheConcurrencyTest {

  @Test
  fun storeConcurrently() = runTest {
    val cacheManager = CacheManager(MemoryCacheFactory(maxSizeBytes = 1000))
    val executor = Executors.newFixedThreadPool(10)
    val dispatcher = executor.asCoroutineDispatcher()

    val apolloClient = ApolloClient.Builder()
        .networkTransport(QueueTestNetworkTransport())
        .cacheManager(cacheManager)
        .dispatcher(dispatcher)
        .build()

    val concurrency = 100

    0.until(concurrency).map {
      launch(dispatcher) {
        val query = CharacterNameByIdQuery((it / 2).toString())
        apolloClient.enqueueTestResponse(query, CharacterNameByIdQuery.Data(CharacterNameByIdQuery.Character("Droid", name = it.toString())))
        apolloClient.query(query).execute()
      }
    }.joinAll()

    executor.shutdown()
    println(cacheManager.dump().values.toList()[1].map { (k, v) -> "$k -> ${v.fields}" }.joinToString("\n"))
  }
}
