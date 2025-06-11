package com.apollographql.cache.normalized.internal

import com.apollographql.apollo.api.ApolloResponse
import com.apollographql.apollo.api.CustomScalarAdapters
import com.apollographql.apollo.api.Error
import com.apollographql.apollo.api.Executable
import com.apollographql.apollo.api.Fragment
import com.apollographql.apollo.api.Operation
import com.apollographql.apollo.api.json.ApolloJsonElement
import com.apollographql.apollo.api.json.jsonReader
import com.apollographql.apollo.api.variables
import com.apollographql.cache.normalized.CacheInfo
import com.apollographql.cache.normalized.CacheManager
import com.apollographql.cache.normalized.CacheManager.ReadResult
import com.apollographql.cache.normalized.api.ApolloCacheHeaders
import com.apollographql.cache.normalized.api.CacheHeaders
import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.api.CacheKeyGenerator
import com.apollographql.cache.normalized.api.CacheResolver
import com.apollographql.cache.normalized.api.DataWithErrors
import com.apollographql.cache.normalized.api.EmbeddedFieldsProvider
import com.apollographql.cache.normalized.api.FieldKeyGenerator
import com.apollographql.cache.normalized.api.MaxAgeProvider
import com.apollographql.cache.normalized.api.MetadataGenerator
import com.apollographql.cache.normalized.api.NormalizedCache
import com.apollographql.cache.normalized.api.NormalizedCacheFactory
import com.apollographql.cache.normalized.api.Record
import com.apollographql.cache.normalized.api.RecordMerger
import com.apollographql.cache.normalized.api.propagateErrors
import com.apollographql.cache.normalized.api.rootKey
import com.apollographql.cache.normalized.api.withErrors
import com.apollographql.cache.normalized.cacheHeaders
import com.apollographql.cache.normalized.cacheInfo
import com.benasher44.uuid.Uuid
import com.benasher44.uuid.uuid4
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.reflect.KClass

internal class DefaultCacheManager(
    normalizedCacheFactory: NormalizedCacheFactory,
    private val cacheKeyGenerator: CacheKeyGenerator,
    private val fieldKeyGenerator: FieldKeyGenerator,
    private val metadataGenerator: MetadataGenerator,
    private val cacheResolver: CacheResolver,
    private val recordMerger: RecordMerger,
    private val embeddedFieldsProvider: EmbeddedFieldsProvider,
    private val maxAgeProvider: MaxAgeProvider,
) : CacheManager {
  private val changedKeysEvents = MutableSharedFlow<Set<String>>(
      /**
       * The '64' magic number here is a potential code smell
       *
       * If a watcher is very slow to collect, cache writes continue buffering changedKeys events until the buffer is full.
       * If that ever happens, this is probably an issue in the calling code, and we currently log that to the user. A more
       * advanced version of this code could also expose the buffer size to the caller for better control.
       *
       * Also, we have had issues before where one or several watchers would loop forever, creating useless network requests.
       * There is unfortunately very little evidence of how it could happen, but I could reproduce under the following conditions:
       * 1. A field that returns ever-changing values (think current time for an example)
       * 2. A refetch policy that uses the network ([com.apollographql.cache.normalized.FetchPolicy.NetworkOnly] or [com.apollographql.cache.normalized.FetchPolicy.CacheFirst] do for an example)
       *
       * In that case, a single watcher will trigger itself endlessly.
       *
       * My current understanding is that here as well, the fix is probably best done at the callsite by not using [com.apollographql.cache.normalized.FetchPolicy.NetworkOnly]
       * as a refetchPolicy. If that ever becomes an issue again, please make sure to write a test about it.
       */
      extraBufferCapacity = 64,
      onBufferOverflow = BufferOverflow.SUSPEND
  )

  override val changedKeys = changedKeysEvents.asSharedFlow()

  // Keeping this as lazy to avoid accessing the disk at initialization which usually happens on the main thread
  private val cache: OptimisticNormalizedCache by lazy {
    OptimisticNormalizedCache(normalizedCacheFactory.create())
  }

  override suspend fun publish(keys: Set<String>) {
    if (keys.isEmpty() && keys !== CacheManager.ALL_KEYS) {
      return
    }

    changedKeysEvents.emit(keys)
  }

  override suspend fun clearAll(): Boolean {
    cache.clearAll()
    return true
  }

  override suspend fun remove(
      cacheKey: CacheKey,
      cascade: Boolean,
  ): Boolean {
    return cache.remove(cacheKey, cascade)
  }

  override suspend fun remove(
      cacheKeys: List<CacheKey>,
      cascade: Boolean,
  ): Int {
    return cache.remove(cacheKeys, cascade)
  }

  override fun <D : Executable.Data> normalize(
      executable: Executable<D>,
      dataWithErrors: DataWithErrors,
      rootKey: CacheKey,
      customScalarAdapters: CustomScalarAdapters,
  ): Map<CacheKey, Record> {
    return dataWithErrors.normalized(
        executable = executable,
        rootKey = rootKey,
        customScalarAdapters = customScalarAdapters,
        cacheKeyGenerator = cacheKeyGenerator,
        metadataGenerator = metadataGenerator,
        fieldKeyGenerator = fieldKeyGenerator,
        embeddedFieldsProvider = embeddedFieldsProvider,
        maxAgeProvider = maxAgeProvider,
    )
  }

  override suspend fun <D : Operation.Data> readOperation(
      operation: Operation<D>,
      customScalarAdapters: CustomScalarAdapters,
      cacheHeaders: CacheHeaders,
  ): ApolloResponse<D> {
    val variables = operation.variables(customScalarAdapters, true)
    val batchReaderData = CacheBatchReader(
        cache = cache,
        cacheHeaders = cacheHeaders,
        cacheResolver = cacheResolver,
        variables = variables,
        rootKey = operation.rootKey(),
        rootSelections = operation.rootField().selections,
        rootField = operation.rootField(),
        fieldKeyGenerator = fieldKeyGenerator,
        returnPartialResponses = true,
    ).collectData()
    val dataWithErrors: DataWithErrors = batchReaderData.toMap()
    val errors = mutableListOf<Error>()

    @Suppress("UNCHECKED_CAST")
    val dataWithNulls: Map<String, ApolloJsonElement>? =
      if (batchReaderData.hasErrors) {
        propagateErrors(dataWithErrors, operation.rootField(), errors)
      } else {
        dataWithErrors
      } as Map<String, ApolloJsonElement>?
    val falseVariablesCustomScalarAdapter =
      customScalarAdapters.newBuilder()
          .falseVariables(variables.valueMap.filter { it.value == false }.keys)
          .errors(errors)
          .build()
    val data = dataWithNulls?.let {
      // Embed the data in a { "data": ... } object to match the expected paths of the operation adapter
      val jsonReader = mapOf("data" to it).jsonReader()
      jsonReader.beginObject()
      jsonReader.nextName()
      val data = operation.adapter().fromJson(jsonReader, falseVariablesCustomScalarAdapter)
      jsonReader.endObject()
      data
    }
    return ApolloResponse.Builder(operation, uuid4())
        .data(data)
        .errors(errors.takeIf { it.isNotEmpty() })
        .cacheHeaders(batchReaderData.cacheHeaders)
        .cacheInfo(
            CacheInfo.Builder()
                .fromCache(true)
                .cacheHit(errors.isEmpty())
                .stale(batchReaderData.cacheHeaders.headerValue(ApolloCacheHeaders.STALE) == "true")
                .build()
        )
        .build()
  }

  override suspend fun <D : Fragment.Data> readFragment(
      fragment: Fragment<D>,
      cacheKey: CacheKey,
      customScalarAdapters: CustomScalarAdapters,
      cacheHeaders: CacheHeaders,
  ): ReadResult<D> {
    val variables = fragment.variables(customScalarAdapters, true)
    val batchReaderData = CacheBatchReader(
        cache = cache,
        cacheHeaders = cacheHeaders,
        cacheResolver = cacheResolver,
        variables = variables,
        rootKey = cacheKey,
        rootSelections = fragment.rootField().selections,
        rootField = fragment.rootField(),
        fieldKeyGenerator = fieldKeyGenerator,
        returnPartialResponses = false,
    ).collectData()
    val dataWithErrors = batchReaderData.toMap(withErrors = false)
    val falseVariablesCustomScalarAdapter =
      customScalarAdapters.newBuilder().falseVariables(variables.valueMap.filter { it.value == false }.keys).build()
    val data = fragment.adapter().fromJson(dataWithErrors.jsonReader(), falseVariablesCustomScalarAdapter)
    return ReadResult(data = data, cacheHeaders = batchReaderData.cacheHeaders)
  }

  override suspend fun <R> accessCache(block: suspend (NormalizedCache) -> R): R {
    return block(cache)
  }

  override suspend fun <D : Operation.Data> writeOperation(
      operation: Operation<D>,
      data: D,
      errors: List<Error>?,
      customScalarAdapters: CustomScalarAdapters,
      cacheHeaders: CacheHeaders,
  ): Set<String> {
    val dataWithErrors = data.withErrors(operation, errors, customScalarAdapters)
    return writeOperation(operation, dataWithErrors, customScalarAdapters, cacheHeaders)
  }

  override suspend fun <D : Operation.Data> writeOperation(
      operation: Operation<D>,
      dataWithErrors: DataWithErrors,
      customScalarAdapters: CustomScalarAdapters,
      cacheHeaders: CacheHeaders,
  ): Set<String> {
    val records = normalize(
        executable = operation,
        dataWithErrors = dataWithErrors,
        rootKey = operation.rootKey(),
        customScalarAdapters = customScalarAdapters,
    ).values.toSet()
    return cache.merge(records, cacheHeaders, recordMerger)
  }

  override suspend fun <D : Fragment.Data> writeFragment(
      fragment: Fragment<D>,
      cacheKey: CacheKey,
      data: D,
      customScalarAdapters: CustomScalarAdapters,
      cacheHeaders: CacheHeaders,
  ): Set<String> {
    val dataWithErrors = data.withErrors(fragment, null, customScalarAdapters)
    val records = normalize(
        executable = fragment,
        dataWithErrors = dataWithErrors,
        rootKey = cacheKey,
        customScalarAdapters = customScalarAdapters,
    ).values
    return cache.merge(records, cacheHeaders, recordMerger)
  }

  override suspend fun <D : Operation.Data> writeOptimisticUpdates(
      operation: Operation<D>,
      data: D,
      mutationId: Uuid,
      customScalarAdapters: CustomScalarAdapters,
  ): Set<String> {
    val dataWithErrors = data.withErrors(operation, null, customScalarAdapters)
    val records = normalize(
        executable = operation,
        dataWithErrors = dataWithErrors,
        rootKey = operation.rootKey(),
        customScalarAdapters = customScalarAdapters,
    ).values.map { record ->
      Record(
          key = record.key,
          fields = record.fields,
          mutationId = mutationId
      )
    }

    /**
     * TODO: should we forward the cache headers to the optimistic store?
     */
    return cache.addOptimisticUpdates(records)
  }

  override suspend fun <D : Fragment.Data> writeOptimisticUpdates(
      fragment: Fragment<D>,
      cacheKey: CacheKey,
      data: D,
      mutationId: Uuid,
      customScalarAdapters: CustomScalarAdapters,
  ): Set<String> {
    val dataWithErrors = data.withErrors(fragment, null, customScalarAdapters)
    val records = normalize(
        executable = fragment,
        dataWithErrors = dataWithErrors,
        rootKey = cacheKey,
        customScalarAdapters = customScalarAdapters,
    ).values.map { record ->
      Record(
          key = record.key,
          fields = record.fields,
          mutationId = mutationId
      )
    }
    return cache.addOptimisticUpdates(records)
  }

  override suspend fun rollbackOptimisticUpdates(
      mutationId: Uuid,
  ): Set<String> {
    return cache.removeOptimisticUpdates(mutationId)
  }

  override suspend fun trim(maxSizeBytes: Long, trimFactor: Float): Long {
    return cache.trim(maxSizeBytes, trimFactor)
  }

  override suspend fun dump(): Map<KClass<*>, Map<CacheKey, Record>> {
    return cache.dump()
  }

  override fun dispose() {}
}
