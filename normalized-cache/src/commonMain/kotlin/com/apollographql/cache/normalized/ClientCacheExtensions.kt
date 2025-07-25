@file:JvmName("NormalizedCache")

package com.apollographql.cache.normalized

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.CacheDumpProviderContext
import com.apollographql.apollo.api.ApolloRequest
import com.apollographql.apollo.api.ApolloResponse
import com.apollographql.apollo.api.MutableExecutionOptions
import com.apollographql.apollo.api.Operation
import com.apollographql.apollo.api.http.get
import com.apollographql.apollo.interceptor.ApolloInterceptor
import com.apollographql.apollo.interceptor.ApolloInterceptorChain
import com.apollographql.apollo.network.http.HttpInfo
import com.apollographql.cache.normalized.api.ApolloCacheHeaders
import com.apollographql.cache.normalized.api.CacheControlCacheResolver
import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.api.CacheKeyGenerator
import com.apollographql.cache.normalized.api.CacheKeyGeneratorContext
import com.apollographql.cache.normalized.api.CacheResolver
import com.apollographql.cache.normalized.api.ConnectionMetadataGenerator
import com.apollographql.cache.normalized.api.ConnectionRecordMerger
import com.apollographql.cache.normalized.api.DefaultEmbeddedFieldsProvider
import com.apollographql.cache.normalized.api.DefaultFieldKeyGenerator
import com.apollographql.cache.normalized.api.DefaultMaxAgeProvider
import com.apollographql.cache.normalized.api.DefaultRecordMerger
import com.apollographql.cache.normalized.api.EmbeddedFieldsProvider
import com.apollographql.cache.normalized.api.EmptyMetadataGenerator
import com.apollographql.cache.normalized.api.FieldKeyGenerator
import com.apollographql.cache.normalized.api.FieldPolicyCacheResolver
import com.apollographql.cache.normalized.api.GlobalMaxAgeProvider
import com.apollographql.cache.normalized.api.MaxAge
import com.apollographql.cache.normalized.api.MaxAgeProvider
import com.apollographql.cache.normalized.api.MetadataGenerator
import com.apollographql.cache.normalized.api.NormalizedCacheFactory
import com.apollographql.cache.normalized.api.RecordMerger
import com.apollographql.cache.normalized.api.SchemaCoordinatesMaxAgeProvider
import com.apollographql.cache.normalized.api.TypePolicy
import com.apollographql.cache.normalized.api.TypePolicyCacheKeyGenerator
import com.apollographql.cache.normalized.internal.ApolloCacheInterceptor
import com.apollographql.cache.normalized.internal.WatcherInterceptor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.jvm.JvmName
import kotlin.jvm.JvmOverloads
import kotlin.time.Duration

/**
 * Configures an [ApolloClient] with a normalized cache.
 *
 * @param normalizedCacheFactory a factory that creates a [com.apollographql.cache.normalized.api.NormalizedCache].
 * It will only be called once.
 * The reason this is a factory is to enforce creating the cache from a non-main thread. For native the thread
 * where the cache is created will also be isolated so that the cache can be mutated.
 *
 * @param cacheResolver a [CacheResolver] to customize normalization
 *
 * @param writeToCacheAsynchronously set to true to write to the cache after the response has been emitted.
 * This allows to display results faster
 */
@JvmOverloads
@JvmName("configureApolloClientBuilder2")
fun ApolloClient.Builder.normalizedCache(
    normalizedCacheFactory: NormalizedCacheFactory,
    cacheKeyGenerator: CacheKeyGenerator = @Suppress("DEPRECATION") TypePolicyCacheKeyGenerator,
    metadataGenerator: MetadataGenerator = EmptyMetadataGenerator,
    cacheResolver: CacheResolver = FieldPolicyCacheResolver(keyScope = CacheKey.Scope.TYPE),
    recordMerger: RecordMerger = DefaultRecordMerger,
    fieldKeyGenerator: FieldKeyGenerator = DefaultFieldKeyGenerator,
    embeddedFieldsProvider: EmbeddedFieldsProvider = DefaultEmbeddedFieldsProvider,
    maxAgeProvider: MaxAgeProvider = DefaultMaxAgeProvider,
    enableOptimisticUpdates: Boolean = false,
    writeToCacheAsynchronously: Boolean = false,
): ApolloClient.Builder {
  return cacheManager(
      CacheManager(
          normalizedCacheFactory = normalizedCacheFactory,
          cacheKeyGenerator = cacheKeyGenerator,
          metadataGenerator = metadataGenerator,
          cacheResolver = cacheResolver,
          recordMerger = recordMerger,
          fieldKeyGenerator = fieldKeyGenerator,
          embeddedFieldsProvider = embeddedFieldsProvider,
          maxAgeProvider = maxAgeProvider,
          enableOptimisticUpdates = enableOptimisticUpdates,
      ), writeToCacheAsynchronously
  )
}

fun ApolloClient.Builder.normalizedCache(
    normalizedCacheFactory: NormalizedCacheFactory,
    typePolicies: Map<String, TypePolicy>,
    connectionTypes: Set<String>,
    maxAges: Map<String, MaxAge>,
    defaultMaxAge: Duration,
    keyScope: CacheKey.Scope,
    enableOptimisticUpdates: Boolean,
    writeToCacheAsynchronously: Boolean,
): ApolloClient.Builder {
  val cacheKeyGenerator = if (typePolicies.isEmpty()) {
    object : CacheKeyGenerator {
      override fun cacheKeyForObject(obj: Map<String, Any?>, context: CacheKeyGeneratorContext): CacheKey? {
        return null
      }
    }
  } else {
    TypePolicyCacheKeyGenerator(typePolicies, keyScope)
  }
  val metadataGenerator = if (connectionTypes.isEmpty()) {
    EmptyMetadataGenerator
  } else {
    ConnectionMetadataGenerator(connectionTypes)
  }
  val maxAgeProvider = if (maxAges.isEmpty()) {
    GlobalMaxAgeProvider(defaultMaxAge)
  } else {
    SchemaCoordinatesMaxAgeProvider(maxAges, defaultMaxAge)
  }
  val cacheResolver = if (maxAges.isEmpty()) {
    FieldPolicyCacheResolver(keyScope)
  } else {
    CacheControlCacheResolver(
        maxAgeProvider = maxAgeProvider,
        delegateResolver = FieldPolicyCacheResolver(keyScope),
    )
  }
  val recordMerger = if (connectionTypes.isEmpty()) {
    DefaultRecordMerger
  } else {
    ConnectionRecordMerger
  }
  return normalizedCache(
      normalizedCacheFactory = normalizedCacheFactory,
      cacheKeyGenerator = cacheKeyGenerator,
      metadataGenerator = metadataGenerator,
      cacheResolver = cacheResolver,
      recordMerger = recordMerger,
      maxAgeProvider = maxAgeProvider,
      enableOptimisticUpdates = enableOptimisticUpdates,
      writeToCacheAsynchronously = writeToCacheAsynchronously,
  )
}

@JvmName("-logCacheMisses")
fun ApolloClient.Builder.logCacheMisses(
    log: (String) -> Unit = { println(it) },
): ApolloClient.Builder {
  return addInterceptor(CacheMissLoggingInterceptor(log))
}

private class DefaultInterceptorChain(
    private val interceptors: List<ApolloInterceptor>,
    private val index: Int,
) : ApolloInterceptorChain {

  override fun <D : Operation.Data> proceed(request: ApolloRequest<D>): Flow<ApolloResponse<D>> {
    check(index < interceptors.size)
    return interceptors[index].intercept(
        request,
        DefaultInterceptorChain(
            interceptors = interceptors,
            index = index + 1,
        )
    )
  }
}

private fun ApolloInterceptorChain.asInterceptor(): ApolloInterceptor {
  return object : ApolloInterceptor {
    override fun <D : Operation.Data> intercept(
        request: ApolloRequest<D>,
        chain: ApolloInterceptorChain,
    ): Flow<ApolloResponse<D>> {
      return this@asInterceptor.proceed(request)
    }
  }
}

internal class CacheInterceptor(val cacheManager: CacheManager) : ApolloInterceptor {
  private val delegates = listOf(
      WatcherInterceptor(cacheManager),
      FetchPolicyRouterInterceptor,
      ApolloCacheInterceptor(cacheManager),
      StoreExpirationDateInterceptor,
  )

  override fun <D : Operation.Data> intercept(
      request: ApolloRequest<D>,
      chain: ApolloInterceptorChain,
  ): Flow<ApolloResponse<D>> {
    return DefaultInterceptorChain(delegates + chain.asInterceptor(), 0).proceed(request)
  }
}

fun ApolloClient.Builder.cacheManager(cacheManager: CacheManager, writeToCacheAsynchronously: Boolean = false): ApolloClient.Builder {
  return cacheInterceptor(CacheInterceptor(cacheManager))
      .writeToCacheAsynchronously(writeToCacheAsynchronously)
      .addExecutionContext(CacheDumpProviderContext(cacheManager.cacheDumpProvider()))
}

val ApolloClient.apolloStore: ApolloStore
  get() {
    return (cacheInterceptor as? CacheInterceptor)?.let {
      ApolloStore(it.cacheManager, customScalarAdapters)
    } ?: error("No store configured")
  }

private object StoreExpirationDateInterceptor : ApolloInterceptor {
  override fun <D : Operation.Data> intercept(request: ApolloRequest<D>, chain: ApolloInterceptorChain): Flow<ApolloResponse<D>> {
    return chain.proceed(request).map {
      val store = request.executionContext[StoreExpirationDateContext]?.value
      if (store != true) {
        return@map it
      }
      val headers = it.executionContext[HttpInfo]?.headers.orEmpty()

      val cacheControl = headers.get("cache-control")?.lowercase() ?: return@map it

      val c = cacheControl.split(",").map { it.trim() }
      val maxAge = c.firstNotNullOfOrNull {
        if (it.startsWith("max-age=")) {
          it.substring(8).toIntOrNull()
        } else {
          null
        }
      } ?: return@map it

      val age = headers.get("age")?.toIntOrNull()
      val expires = if (age != null) {
        request.clock() / 1000 + maxAge - age
      } else {
        request.clock() / 1000 + maxAge
      }

      return@map it.newBuilder()
          .cacheHeaders(
              it.cacheHeaders.newBuilder()
                  .addHeader(ApolloCacheHeaders.EXPIRATION_DATE, expires.toString())
                  .build()
          )
          .build()
    }
  }
}

@Deprecated(level = DeprecationLevel.ERROR, message = "This method has no effect and will be removed in a future release. Partial responses are always stored in the cache.")
fun <T> MutableExecutionOptions<T>.storePartialResponses(storePartialResponses: Boolean): Nothing = throw UnsupportedOperationException()
