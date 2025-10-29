package test

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Adapter
import com.apollographql.apollo.api.CustomScalarAdapters
import com.apollographql.apollo.api.json.JsonNumber
import com.apollographql.apollo.api.json.JsonReader
import com.apollographql.apollo.api.json.JsonWriter
import com.apollographql.apollo.testing.QueueTestNetworkTransport
import com.apollographql.apollo.testing.enqueueTestResponse
import com.apollographql.cache.normalized.FetchPolicy
import com.apollographql.cache.normalized.api.DefaultCacheKeyGenerator
import com.apollographql.cache.normalized.api.DefaultCacheResolver
import com.apollographql.cache.normalized.fetchPolicy
import com.apollographql.cache.normalized.memory.MemoryCacheFactory
import com.apollographql.cache.normalized.normalizedCache
import com.apollographql.cache.normalized.testing.runTest
import com.example.GetNumberQuery
import okio.use
import kotlin.test.Test
import kotlin.test.assertEquals

class NumberTest {
  @Test
  fun test() = runTest {
    ApolloClient.Builder()
        .networkTransport(QueueTestNetworkTransport())
        .normalizedCache(MemoryCacheFactory(), cacheKeyGenerator = DefaultCacheKeyGenerator, cacheResolver = DefaultCacheResolver)
        .addCustomScalarAdapter(com.example.type.Number.type, NumberAdapter())
        .build()
        .use { apolloClient ->
          apolloClient.enqueueTestResponse(GetNumberQuery(), GetNumberQuery.Data("12345"))
          apolloClient.query(GetNumberQuery()).fetchPolicy(FetchPolicy.NetworkOnly).execute().apply {
            assertEquals("12345", data?.number)
          }
          apolloClient.query(GetNumberQuery()).fetchPolicy(FetchPolicy.CacheOnly).execute().apply {
            assertEquals("12345", data?.number)
          }
        }
  }
}

class NumberAdapter : Adapter<String> {
  override fun fromJson(reader: JsonReader, customScalarAdapters: CustomScalarAdapters): String {
    return reader.nextNumber().value
  }

  override fun toJson(writer: JsonWriter, customScalarAdapters: CustomScalarAdapters, value: String) {
    writer.value(JsonNumber(value))
  }
}
