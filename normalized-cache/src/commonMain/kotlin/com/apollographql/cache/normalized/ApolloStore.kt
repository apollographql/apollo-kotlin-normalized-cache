package com.apollographql.cache.normalized

import com.apollographql.apollo.api.ApolloResponse
import com.apollographql.apollo.api.CustomScalarAdapters
import com.apollographql.apollo.api.Error
import com.apollographql.apollo.api.Executable
import com.apollographql.apollo.api.Fragment
import com.apollographql.apollo.api.Operation
import com.apollographql.cache.normalized.CacheManager.ReadResult
import com.apollographql.cache.normalized.api.CacheHeaders
import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.api.DataWithErrors
import com.apollographql.cache.normalized.api.DefaultRecordMerger
import com.apollographql.cache.normalized.api.NormalizedCache
import com.apollographql.cache.normalized.api.Record
import com.apollographql.cache.normalized.api.rootKey
import com.apollographql.cache.normalized.api.withErrors
import com.benasher44.uuid.Uuid
import kotlinx.coroutines.flow.SharedFlow
import kotlin.reflect.KClass

/**
 * A wrapper around [CacheManager] that provides a simplified API for reading and writing data.
 */
class ApolloStore(
    val cacheManager: CacheManager,
    val customScalarAdapters: CustomScalarAdapters,
) {
  /**
   * @see CacheManager.changedKeys
   */
  val changedKeys: SharedFlow<Set<String>>
    get() = cacheManager.changedKeys

  /**
   * @see CacheManager.readOperation
   */
  suspend fun <D : Operation.Data> readOperation(
      operation: Operation<D>,
      cacheHeaders: CacheHeaders = CacheHeaders.NONE,
  ): ApolloResponse<D> = cacheManager.readOperation(
      operation = operation,
      cacheHeaders = cacheHeaders,
      customScalarAdapters = customScalarAdapters,
  )

  /**
   * @see CacheManager.readFragment
   */
  suspend fun <D : Fragment.Data> readFragment(
      fragment: Fragment<D>,
      cacheKey: CacheKey,
      cacheHeaders: CacheHeaders = CacheHeaders.NONE,
  ): ReadResult<D> = cacheManager.readFragment(
      fragment = fragment,
      cacheKey = cacheKey,
      customScalarAdapters = customScalarAdapters,
      cacheHeaders = cacheHeaders,
  )

  /**
   * @see CacheManager.writeOperation
   */
  suspend fun <D : Operation.Data> writeOperation(
      operation: Operation<D>,
      data: D,
      errors: List<Error>? = null,
      cacheHeaders: CacheHeaders = CacheHeaders.NONE,
  ): Set<String> = cacheManager.writeOperation(
      operation = operation,
      data = data,
      errors = errors,
      cacheHeaders = cacheHeaders,
      customScalarAdapters = customScalarAdapters,
  )

  /**
   * @see CacheManager.writeFragment
   */
  suspend fun <D : Operation.Data> writeOperation(
      operation: Operation<D>,
      dataWithErrors: DataWithErrors,
      cacheHeaders: CacheHeaders = CacheHeaders.NONE,
  ): Set<String> = cacheManager.writeOperation(
      operation = operation,
      dataWithErrors = dataWithErrors,
      cacheHeaders = cacheHeaders,
      customScalarAdapters = customScalarAdapters,
  )

  /**
   * @see CacheManager.writeFragment
   */
  suspend fun <D : Fragment.Data> writeFragment(
      fragment: Fragment<D>,
      cacheKey: CacheKey,
      data: D,
      cacheHeaders: CacheHeaders = CacheHeaders.NONE,
  ): Set<String> = cacheManager.writeFragment(
      fragment = fragment,
      cacheKey = cacheKey,
      data = data,
      cacheHeaders = cacheHeaders,
      customScalarAdapters = customScalarAdapters,
  )

  /**
   * @see CacheManager.writeOptimisticUpdates
   */
  suspend fun <D : Operation.Data> writeOptimisticUpdates(
      operation: Operation<D>,
      data: D,
      mutationId: Uuid,
  ): Set<String> = cacheManager.writeOptimisticUpdates(
      operation = operation,
      data = data,
      mutationId = mutationId,
      customScalarAdapters = customScalarAdapters,
  )

  /**
   * @see CacheManager.writeOptimisticUpdates
   */
  suspend fun <D : Fragment.Data> writeOptimisticUpdates(
      fragment: Fragment<D>,
      cacheKey: CacheKey,
      data: D,
      mutationId: Uuid,
  ): Set<String> = cacheManager.writeOptimisticUpdates(
      fragment = fragment,
      cacheKey = cacheKey,
      data = data,
      mutationId = mutationId,
      customScalarAdapters = customScalarAdapters,
  )

  /**
   * @see CacheManager.rollbackOptimisticUpdates
   */
  suspend fun rollbackOptimisticUpdates(mutationId: Uuid): Set<String> = cacheManager.rollbackOptimisticUpdates(mutationId)

  /**
   * @see CacheManager.clearAll
   */
  suspend fun clearAll(): Boolean = cacheManager.clearAll()

  /**
   * @see CacheManager.remove
   */
  suspend fun remove(cacheKey: CacheKey, cascade: Boolean = true): Boolean = cacheManager.remove(cacheKey, cascade)

  /**
   * @see CacheManager.remove
   */
  suspend fun remove(cacheKeys: List<CacheKey>, cascade: Boolean = true): Int = cacheManager.remove(cacheKeys, cascade)

  /**
   * @see CacheManager.trim
   */
  suspend fun trim(maxSizeBytes: Long, trimFactor: Float = 0.1f): Long = cacheManager.trim(maxSizeBytes, trimFactor)

  /**
   * @see CacheManager.normalize
   */
  fun <D : Executable.Data> normalize(
      executable: Executable<D>,
      dataWithErrors: DataWithErrors,
      rootKey: CacheKey = CacheKey.QUERY_ROOT,
  ): Map<CacheKey, Record> = cacheManager.normalize(
      executable = executable,
      dataWithErrors = dataWithErrors,
      rootKey = rootKey,
      customScalarAdapters = customScalarAdapters,
  )

  /**
   * @see CacheManager.publish
   */
  suspend fun publish(keys: Set<String>) = cacheManager.publish(keys)

  /**
   * @see CacheManager.publish
   */
  suspend fun <R> accessCache(block: suspend (NormalizedCache) -> R): R = cacheManager.accessCache(block)

  /**
   * @see CacheManager.dump
   */
  suspend fun dump(): Map<KClass<*>, Map<CacheKey, Record>> = cacheManager.dump()

  /**
   * @see CacheManager.dispose
   */
  fun dispose() = cacheManager.dispose()
}

/**
 * Removes an operation from the store.
 *
 * This is a synchronous operation that might block if the underlying cache is doing IO.
 *
 * Call [publish] with the returned keys to notify any watchers.
 *
 * @param operation the operation of the data to remove.
 * @param data the data to remove.
 * @return the set of field keys that have been removed.
 */
suspend fun <D : Operation.Data> ApolloStore.removeOperation(
    operation: Operation<D>,
    data: D,
    cacheHeaders: CacheHeaders = CacheHeaders.NONE,
): Set<String> {
  return removeData(operation, operation.rootKey(), data, cacheHeaders)
}

/**
 * Removes a fragment from the store.
 *
 * This is a synchronous operation that might block if the underlying cache is doing IO.
 *
 * Call [publish] with the returned keys to notify any watchers.
 *
 * @param fragment the fragment of the data to remove.
 * @param data the data to remove.
 * @param cacheKey the root where to remove the fragment data from.
 * @return the set of field keys that have been removed.
 */
suspend fun <D : Fragment.Data> ApolloStore.removeFragment(
    fragment: Fragment<D>,
    cacheKey: CacheKey,
    data: D,
    cacheHeaders: CacheHeaders = CacheHeaders.NONE,
): Set<String> {
  return removeData(fragment, cacheKey, data, cacheHeaders)
}

private suspend fun <D : Executable.Data> ApolloStore.removeData(
    executable: Executable<D>,
    cacheKey: CacheKey,
    data: D,
    cacheHeaders: CacheHeaders,
): Set<String> {
  val dataWithErrors = data.withErrors(executable, null)
  val normalizationRecords = normalize(
      executable = executable,
      dataWithErrors = dataWithErrors,
      rootKey = cacheKey,
  )
  val fullRecords = accessCache { cache -> cache.loadRecords(normalizationRecords.map { it.key }, cacheHeaders = cacheHeaders) }
  val trimmedRecords = fullRecords.map { fullRecord ->
    val fieldNamesToTrim = normalizationRecords[fullRecord.key]?.fields?.keys.orEmpty()
    Record(
        key = fullRecord.key,
        fields = fullRecord.fields - fieldNamesToTrim,
        metadata = fullRecord.metadata - fieldNamesToTrim,
    )
  }.filterNot { it.fields.isEmpty() }
  accessCache { cache ->
    cache.remove(normalizationRecords.keys, cascade = false)
    cache.merge(
        records = trimmedRecords,
        cacheHeaders = cacheHeaders,
        recordMerger = DefaultRecordMerger
    )
  }
  return normalizationRecords.values.flatMap { it.fieldKeys() }.toSet()
}
