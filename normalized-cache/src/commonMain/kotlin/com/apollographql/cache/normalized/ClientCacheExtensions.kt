@file:JvmName("NormalizedCache")

package com.apollographql.cache.normalized

import com.apollographql.apollo.ApolloCall
import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.CacheDumpProviderContext
import com.apollographql.apollo.api.ApolloRequest
import com.apollographql.apollo.api.ApolloResponse
import com.apollographql.apollo.api.ExecutionContext
import com.apollographql.apollo.api.ExecutionOptions
import com.apollographql.apollo.api.MutableExecutionOptions
import com.apollographql.apollo.api.Mutation
import com.apollographql.apollo.api.Operation
import com.apollographql.apollo.api.Query
import com.apollographql.apollo.api.http.get
import com.apollographql.apollo.exception.ApolloException
import com.apollographql.apollo.exception.CacheMissException
import com.apollographql.apollo.interceptor.ApolloInterceptor
import com.apollographql.apollo.interceptor.ApolloInterceptorChain
import com.apollographql.apollo.mpp.currentTimeMillis
import com.apollographql.apollo.network.http.HttpInfo
import com.apollographql.cache.normalized.api.ApolloCacheHeaders
import com.apollographql.cache.normalized.api.CacheControlCacheResolver
import com.apollographql.cache.normalized.api.CacheHeaders
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
import com.apollographql.cache.normalized.internal.WatcherSentinel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
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

/**
 * Gets initial response(s) then observes the cache for any changes.
 *
 * There is a guarantee that the cache is subscribed before the initial response(s) finish emitting.
 * Any update to the cache done after the initial response(s) are received will be received.
 *
 * [fetchPolicy] controls how the result is first queried, while [refetchPolicy] will control the subsequent fetches.
 *
 * Note: when manually updating the cache through [ApolloStore], [ApolloStore.publish] must be called for watchers to be notified.
 *
 * @see fetchPolicy
 * @see refetchPolicy
 */
fun <D : Query.Data> ApolloCall<D>.watch(): Flow<ApolloResponse<D>> {
  return flow {
    var lastResponse: ApolloResponse<D>? = null
    var response: ApolloResponse<D>? = null

    toFlow()
        .collect {
          response = it

          if (it.isLast) {
            if (lastResponse != null) {
              /**
               * If we ever come here it means some interceptors built a new Flow and forgot to reset the isLast flag
               * Better safe than sorry: emit them when we realize that. This will introduce a delay in the response.
               */
              println("ApolloGraphQL: extra response received after the last one")
              emit(lastResponse!!)
            }
            /**
             * Remember the last response so that we can send it after we subscribe to the store
             *
             * This allows callers to use the last element as a synchronisation point to modify the store and still have the watcher
             * receive subsequent updates
             *
             * See https://github.com/apollographql/apollo-kotlin/pull/3853
             */
            lastResponse = it
          } else {
            emit(it)
          }
        }


    copy().fetchPolicyInterceptor(refetchPolicyInterceptor)
        .fetchCacheOptions(refetchCacheOptions)
        .watchInternal(response?.data)
        .collect {
          if (it.exception === WatcherSentinel) {
            if (lastResponse != null) {
              emit(lastResponse!!)
              lastResponse = null
            }
          } else {
            emit(it)
          }
        }
  }
}

/**
 * Observes the cache for the given data. Unlike [watch], no initial request is executed on the network.
 * The fetch policy set by [fetchPolicy] will be used.
 */
fun <D : Query.Data> ApolloCall<D>.watch(data: D?): Flow<ApolloResponse<D>> {
  return watchInternal(data).filter { it.exception !== WatcherSentinel }
}

/**
 * Observes the cache for the given data. Unlike [watch], no initial request is executed on the network.
 * The fetch policy set by [fetchPolicy] will be used.
 */
internal fun <D : Query.Data> ApolloCall<D>.watchInternal(data: D?): Flow<ApolloResponse<D>> {
  return copy().addExecutionContext(WatchContext(data)).toFlow()
}

val ApolloClient.apolloStore: ApolloStore
  get() {
    return (cacheInterceptor as? CacheInterceptor)?.let {
      ApolloStore(it.cacheManager, customScalarAdapters)
    } ?: error("No store configured")
  }


/**
 * Sets the initial [FetchPolicy]
 * This only has effects for queries. Mutations and subscriptions always use the network only.
 */
@Deprecated("Use noCache(), onlyIfCached() or reload() instead")
@Suppress("DEPRECATION")
fun <T> MutableExecutionOptions<T>.fetchPolicy(fetchPolicy: FetchPolicy): T {
  return if (fetchPolicy == FetchPolicy.NetworkFirst) {
    // NetworkFirst is deprecated but should still work
    fetchPolicyInterceptor(NetworkFirstInterceptor)
  } else {
    addExecutionContext(FetchCacheOptionsContext(cacheOptionsFor(fetchPolicy)))
  }
}

/**
 * Sets the [FetchPolicy] used when watching queries and a cache change has been published
 */
@Deprecated("Use refetchCacheOptions() instead")
@Suppress("DEPRECATION")
fun <T> MutableExecutionOptions<T>.refetchPolicy(@Suppress("DEPRECATION") fetchPolicy: FetchPolicy): T {
  return if (fetchPolicy == FetchPolicy.NetworkFirst) {
    // NetworkFirst is deprecated but should still work
    refetchPolicyInterceptor(NetworkFirstInterceptor)
  } else {
    addExecutionContext(RefetchCacheOptionsContext(cacheOptionsFor(fetchPolicy)))
  }
}

/**
 * Sets the initial [FetchPolicy]
 * This only has effects for queries. Mutations and subscriptions always use [FetchPolicy.NetworkOnly]
 */
fun <T> MutableExecutionOptions<T>.fetchPolicyInterceptor(interceptor: ApolloInterceptor) = addExecutionContext(
    FetchPolicyContext(interceptor)
)

/**
 * Sets the [FetchPolicy] used when watching queries and a cache change has been published
 */
fun <T> MutableExecutionOptions<T>.refetchPolicyInterceptor(interceptor: ApolloInterceptor) = addExecutionContext(
    RefetchPolicyContext(interceptor)
)

@Suppress("DEPRECATION")
private fun cacheOptionsFor(fetchPolicy: FetchPolicy) = when (fetchPolicy) {
  FetchPolicy.CacheOnly -> CacheOptionsImpl(onlyIfCached = true)
  FetchPolicy.NetworkOnly -> CacheOptionsImpl(noCache = true)
  FetchPolicy.CacheFirst -> CacheOptionsImpl()
  FetchPolicy.NetworkFirst -> CacheOptionsImpl()
  FetchPolicy.CacheAndNetwork -> CacheOptionsImpl(reload = true)
}

/**
 * @param doNotStore Whether to store the response in cache.
 *
 * Default: false
 */
fun <T> MutableExecutionOptions<T>.doNotStore(doNotStore: Boolean) = addExecutionContext(
    DoNotStoreContext(doNotStore)
)

/**
 * @param memoryCacheOnly Whether to store and read from a memory cache only.
 *
 * Default: false
 */
fun <T> MutableExecutionOptions<T>.memoryCacheOnly(memoryCacheOnly: Boolean) = addExecutionContext(
    MemoryCacheOnlyContext(memoryCacheOnly)
)

@Deprecated(level = DeprecationLevel.ERROR, message = "This method has no effect and will be removed in a future release. Partial responses are always stored in the cache.")
fun <T> MutableExecutionOptions<T>.storePartialResponses(storePartialResponses: Boolean): Nothing = throw UnsupportedOperationException()

/**
 * @param storeReceivedDate Whether to store the receive date in the cache.
 *
 * Default: false
 */
fun <T> MutableExecutionOptions<T>.storeReceivedDate(storeReceivedDate: Boolean) = addExecutionContext(
    StoreReceivedDateContext(storeReceivedDate)
)

/**
 * @param errorsReplaceCachedValues Whether field errors should replace existing values in the cache (true) or be discarded (false).
 *
 * Default: false
 */
fun <T> MutableExecutionOptions<T>.errorsReplaceCachedValues(errorsReplaceCachedValues: Boolean) = addExecutionContext(
    ErrorsReplaceCachedValuesContext(errorsReplaceCachedValues)
)

/**
 * @param storeExpirationDate Whether to store the expiration date in the cache.
 *
 * The expiration date is computed from the response HTTP headers
 *
 * Default: false
 */
fun <T> MutableExecutionOptions<T>.storeExpirationDate(storeExpirationDate: Boolean): T {
  addExecutionContext(StoreExpirationDateContext(storeExpirationDate))
  @Suppress("UNCHECKED_CAST")
  return this as T
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

/**
 * @param cacheHeaders additional cache headers to be passed to your [com.apollographql.cache.normalized.api.NormalizedCache]
 */
fun <T> MutableExecutionOptions<T>.cacheHeaders(cacheHeaders: CacheHeaders) = addExecutionContext(
    CacheHeadersContext(cacheHeaders)
)

/**
 * Add a cache header to be passed to your [com.apollographql.cache.normalized.api.NormalizedCache]
 */
fun <T> MutableExecutionOptions<T>.addCacheHeader(key: String, value: String) = cacheHeaders(
    cacheHeaders.newBuilder().addHeader(key, value).build()
)

/**
 * @param maxStale how long to accept stale fields
 */
fun <T> MutableExecutionOptions<T>.maxStale(maxStale: Duration) = addCacheHeader(
    ApolloCacheHeaders.MAX_STALE, maxStale.inWholeSeconds.toString()
)

/**
 * @param writeToCacheAsynchronously whether to return the response before writing it to the cache
 *
 * Setting this to true reduces the latency
 *
 * Default: false
 */
fun <T> MutableExecutionOptions<T>.writeToCacheAsynchronously(writeToCacheAsynchronously: Boolean) = addExecutionContext(
    WriteToCacheAsynchronouslyContext(writeToCacheAsynchronously)
)

/**
 * Sets the optimistic updates to write to the cache while a query is pending.
 */
fun <D : Mutation.Data> ApolloRequest.Builder<D>.optimisticUpdates(data: D) = addExecutionContext(
    OptimisticUpdatesContext(data)
)

fun <D : Mutation.Data> ApolloCall<D>.optimisticUpdates(data: D) = addExecutionContext(
    OptimisticUpdatesContext(data)
)

internal val <D : Operation.Data> ApolloRequest<D>.fetchPolicyInterceptor
  get() = executionContext[FetchPolicyContext]?.interceptor ?: DefaultFetchPolicyInterceptor

private val <T> MutableExecutionOptions<T>.refetchPolicyInterceptor
  get() = executionContext[RefetchPolicyContext]?.interceptor ?: DefaultFetchPolicyInterceptor

internal val <D : Operation.Data> ApolloRequest<D>.doNotStore
  get() = executionContext[DoNotStoreContext]?.value ?: false

internal val <D : Operation.Data> ApolloRequest<D>.memoryCacheOnly
  get() = executionContext[MemoryCacheOnlyContext]?.value ?: false

internal val <D : Operation.Data> ApolloRequest<D>.storeReceivedDate
  get() = executionContext[StoreReceivedDateContext]?.value ?: false

internal val <D : Operation.Data> ApolloRequest<D>.writeToCacheAsynchronously
  get() = executionContext[WriteToCacheAsynchronouslyContext]?.value ?: false

internal val <D : Mutation.Data> ApolloRequest<D>.optimisticData
  get() = executionContext[OptimisticUpdatesContext]?.value

internal val ExecutionOptions.cacheHeaders: CacheHeaders
  get() = executionContext[CacheHeadersContext]?.value ?: CacheHeaders.NONE

internal val <D : Operation.Data> ApolloRequest<D>.watchContext: WatchContext?
  get() = executionContext[WatchContext]

internal val <D : Operation.Data> ApolloRequest<D>.errorsReplaceCachedValues
  get() = executionContext[ErrorsReplaceCachedValuesContext]?.value ?: false

class CacheInfo private constructor(
    val cacheStartMillis: Long,
    val cacheEndMillis: Long,
    val networkStartMillis: Long,
    val networkEndMillis: Long,

    /**
     * True if the response is from the cache, false if it's from the network.
     */
    val isFromCache: Boolean,

    /**
     * True if **all** the fields are found in the cache, false for full or partial cache misses.
     */
    val isCacheHit: Boolean,

    /**
     * The exception that occurred while reading the cache.
     */
    val cacheMissException: CacheMissException?,

    /**
     * The exception that occurred while reading the network.
     */
    val networkException: ApolloException?,

    /**
     * True if at least one field in the response is stale.
     * Always `false` if [isFromCache] is false.
     */
    val isStale: Boolean,
) : ExecutionContext.Element {
  override val key: ExecutionContext.Key<*>
    get() = Key

  companion object Key : ExecutionContext.Key<CacheInfo>

  fun newBuilder(): Builder {
    return Builder().cacheStartMillis(cacheStartMillis)
        .cacheEndMillis(cacheEndMillis)
        .networkStartMillis(networkStartMillis)
        .networkEndMillis(networkEndMillis)
        .fromCache(isFromCache)
        .cacheHit(isCacheHit)
        .cacheMissException(cacheMissException)
        .networkException(networkException)
        .stale(isStale)
  }

  class Builder {
    private var cacheStartMillis: Long = 0
    private var cacheEndMillis: Long = 0
    private var networkStartMillis: Long = 0
    private var networkEndMillis: Long = 0
    private var fromCache: Boolean = false
    private var cacheHit: Boolean = false
    private var cacheMissException: CacheMissException? = null
    private var networkException: ApolloException? = null
    private var stale: Boolean = false

    fun cacheStartMillis(cacheStartMillis: Long) = apply {
      this.cacheStartMillis = cacheStartMillis
    }

    fun cacheEndMillis(cacheEndMillis: Long) = apply {
      this.cacheEndMillis = cacheEndMillis
    }

    fun networkStartMillis(networkStartMillis: Long) = apply {
      this.networkStartMillis = networkStartMillis
    }

    fun networkEndMillis(networkEndMillis: Long) = apply {
      this.networkEndMillis = networkEndMillis
    }

    fun fromCache(fromCache: Boolean) = apply {
      this.fromCache = fromCache
    }

    fun cacheHit(cacheHit: Boolean) = apply {
      this.cacheHit = cacheHit
    }

    fun cacheMissException(cacheMissException: CacheMissException?) = apply {
      this.cacheMissException = cacheMissException
    }

    fun networkException(networkException: ApolloException?) = apply {
      this.networkException = networkException
    }

    fun stale(stale: Boolean) = apply {
      this.stale = stale
    }

    fun build(): CacheInfo = CacheInfo(
        cacheStartMillis = cacheStartMillis,
        cacheEndMillis = cacheEndMillis,
        networkStartMillis = networkStartMillis,
        networkEndMillis = networkEndMillis,
        isFromCache = fromCache,
        isCacheHit = cacheHit,
        cacheMissException = cacheMissException,
        networkException = networkException,
        isStale = stale,
    )
  }
}

/**
 * True if this response comes from the cache, false if it comes from the network.
 *
 * Note that this can be true regardless of whether the data was found in the cache.
 * To know whether the **data** is from the cache, use `cacheInfo?.isCacheHit == true`.
 */
val <D : Operation.Data> ApolloResponse<D>.isFromCache: Boolean
  get() {
    return cacheInfo?.isFromCache == true
  }

val <D : Operation.Data> ApolloResponse<D>.cacheInfo
  get() = executionContext[CacheInfo]

internal fun <D : Operation.Data> ApolloResponse<D>.withCacheInfo(cacheInfo: CacheInfo) =
  newBuilder().addExecutionContext(cacheInfo).build()

internal fun <D : Operation.Data> ApolloResponse.Builder<D>.cacheInfo(cacheInfo: CacheInfo) = addExecutionContext(cacheInfo)

internal class FetchPolicyContext(val interceptor: ApolloInterceptor) : ExecutionContext.Element {
  override val key: ExecutionContext.Key<*>
    get() = Key

  companion object Key : ExecutionContext.Key<FetchPolicyContext>
}

internal class RefetchPolicyContext(val interceptor: ApolloInterceptor) : ExecutionContext.Element {
  override val key: ExecutionContext.Key<*>
    get() = Key

  companion object Key : ExecutionContext.Key<RefetchPolicyContext>
}

internal class DoNotStoreContext(val value: Boolean) : ExecutionContext.Element {
  override val key: ExecutionContext.Key<*>
    get() = Key

  companion object Key : ExecutionContext.Key<DoNotStoreContext>
}

internal class MemoryCacheOnlyContext(val value: Boolean) : ExecutionContext.Element {
  override val key: ExecutionContext.Key<*>
    get() = Key

  companion object Key : ExecutionContext.Key<MemoryCacheOnlyContext>
}

internal class StoreReceivedDateContext(val value: Boolean) : ExecutionContext.Element {
  override val key: ExecutionContext.Key<*>
    get() = Key

  companion object Key : ExecutionContext.Key<StoreReceivedDateContext>
}

internal class StoreExpirationDateContext(val value: Boolean) : ExecutionContext.Element {
  override val key: ExecutionContext.Key<*>
    get() = Key

  companion object Key : ExecutionContext.Key<StoreExpirationDateContext>
}

internal class WriteToCacheAsynchronouslyContext(val value: Boolean) : ExecutionContext.Element {
  override val key: ExecutionContext.Key<*>
    get() = Key

  companion object Key : ExecutionContext.Key<WriteToCacheAsynchronouslyContext>
}

internal class CacheHeadersContext(val value: CacheHeaders) : ExecutionContext.Element {
  override val key: ExecutionContext.Key<*>
    get() = Key

  companion object Key : ExecutionContext.Key<CacheHeadersContext>
}

internal class OptimisticUpdatesContext<D : Mutation.Data>(val value: D) : ExecutionContext.Element {
  override val key: ExecutionContext.Key<*>
    get() = Key

  companion object Key : ExecutionContext.Key<OptimisticUpdatesContext<*>>
}

internal class WatchContext(
    val data: Query.Data?,
) : ExecutionContext.Element {
  override val key: ExecutionContext.Key<*>
    get() = Key

  companion object Key : ExecutionContext.Key<WatchContext>
}

internal class FetchFromCacheContext(val value: Boolean) : ExecutionContext.Element {
  override val key: ExecutionContext.Key<*>
    get() = Key

  companion object Key : ExecutionContext.Key<FetchFromCacheContext>
}

internal class ErrorsReplaceCachedValuesContext(val value: Boolean) : ExecutionContext.Element {
  override val key: ExecutionContext.Key<*>
    get() = Key

  companion object Key : ExecutionContext.Key<ErrorsReplaceCachedValuesContext>
}

fun <D : Operation.Data> ApolloRequest.Builder<D>.fetchFromCache(fetchFromCache: Boolean) = apply {
  addExecutionContext(FetchFromCacheContext(fetchFromCache))
}

val <D : Operation.Data> ApolloRequest<D>.fetchFromCache
  get() = executionContext[FetchFromCacheContext]?.value ?: false

fun <D : Operation.Data> ApolloResponse.Builder<D>.cacheHeaders(cacheHeaders: CacheHeaders) =
  addExecutionContext(CacheHeadersContext(cacheHeaders))

val <D : Operation.Data> ApolloResponse<D>.cacheHeaders
  get() = executionContext[CacheHeadersContext]?.value ?: CacheHeaders.NONE


internal class ClockContext(val value: () -> Long) : ExecutionContext.Element {
  override val key: ExecutionContext.Key<*>
    get() = Key

  companion object Key : ExecutionContext.Key<ClockContext>
}

internal val ExecutionOptions.clock: (() -> Long)
  get() = executionContext[ClockContext]?.value ?: { currentTimeMillis() }

/**
 * Sets the clock used to:
 * - compute the expiration date (see [storeExpirationDate])
 * - get the received date (see [storeReceivedDate])
 * - comparing these dates to the current time in [CacheControlCacheResolver]
 *
 * This is useful for testing purposes only.
 *
 * @param clock returns the current time in milliseconds since the epoch.
 */
fun <T> MutableExecutionOptions<T>.clock(clock: () -> Long): T {
  addExecutionContext(ClockContext(clock))
  @Suppress("UNCHECKED_CAST")
  return this as T
}

internal class FetchCacheOptionsContext(val value: CacheOptionsImpl) : ExecutionContext.Element {
  override val key: ExecutionContext.Key<*>
    get() = Key

  companion object Key : ExecutionContext.Key<FetchCacheOptionsContext>
}

internal val ExecutionOptions.fetchCacheOptions: CacheOptionsImpl
  get() = executionContext[FetchCacheOptionsContext]?.value ?: CacheOptionsImpl()

internal fun <T> MutableExecutionOptions<T>.fetchCacheOptions(fetchCacheOptions: CacheOptionsImpl): T {
  addExecutionContext(FetchCacheOptionsContext(fetchCacheOptions))
  @Suppress("UNCHECKED_CAST")
  return this as T
}


fun <T> MutableExecutionOptions<T>.allowCachedPartialResults(allowCachedPartialResults: Boolean): T =
  addExecutionContext(FetchCacheOptionsContext(fetchCacheOptions.also { it.allowCachedPartialResults(allowCachedPartialResults) }))

fun <T> MutableExecutionOptions<T>.allowCachedErrors(allowCachedErrors: Boolean): T =
  addExecutionContext(FetchCacheOptionsContext(fetchCacheOptions.also { it.allowCachedErrors(allowCachedErrors) }))

fun <T> MutableExecutionOptions<T>.noCache(noCache: Boolean): T =
  addExecutionContext(FetchCacheOptionsContext(fetchCacheOptions.also { it.noCache(noCache) }))

fun <T> MutableExecutionOptions<T>.onlyIfCached(onlyIfCached: Boolean): T =
  addExecutionContext(FetchCacheOptionsContext(fetchCacheOptions.also { it.onlyIfCached(onlyIfCached) }))

fun <T> MutableExecutionOptions<T>.reload(reload: Boolean): T =
  addExecutionContext(FetchCacheOptionsContext(fetchCacheOptions.also { it.reload(reload) }))


internal class RefetchCacheOptionsContext(val value: CacheOptionsImpl) : ExecutionContext.Element {
  override val key: ExecutionContext.Key<*>
    get() = Key

  companion object Key : ExecutionContext.Key<RefetchCacheOptionsContext>
}

internal val ExecutionOptions.refetchCacheOptions: CacheOptionsImpl
  get() = executionContext[RefetchCacheOptionsContext]?.value ?: CacheOptionsImpl(onlyIfCached = true)

fun <T> MutableExecutionOptions<T>.refetchCacheOptions(refetchCacheOptions: CacheOptions.() -> Unit): T =
  addExecutionContext(RefetchCacheOptionsContext(this.refetchCacheOptions.also { refetchCacheOptions(it) }))
