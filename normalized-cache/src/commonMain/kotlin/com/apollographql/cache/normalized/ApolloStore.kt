package com.apollographql.cache.normalized

import com.apollographql.apollo.api.ApolloResponse
import com.apollographql.apollo.api.CustomScalarAdapters
import com.apollographql.apollo.api.Error
import com.apollographql.apollo.api.Executable
import com.apollographql.apollo.api.Fragment
import com.apollographql.apollo.api.Operation
import com.apollographql.cache.normalized.CacheManager.Companion.ALL_KEYS
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
 *
 * Although all operations are `suspend` functions, they may **suspend** or **block** the thread depending on the underlying cache
 * implementation. For example, the SQL cache implementation on Android will **block** the thread while accessing the disk. As such,
 * these operations **must not** run on the main thread. You can enclose them in a [kotlinx.coroutines.withContext] block with a
 * `Dispatchers.IO` context to ensure that they run on a background thread.
 *
 * Note that changes are not automatically published - call [publish] to notify any watchers.
 */
class ApolloStore(
    val cacheManager: CacheManager,
    val customScalarAdapters: CustomScalarAdapters,
) {
  /**
   * Exposes the record field keys that have changed. This is collected internally to notify watchers.
   *
   * A special value [ALL_KEYS] indicates that all records have changed.
   *
   * @see publish
   * @see watch
   *
   * @see CacheManager.changedKeys
   */
  val changedKeys: SharedFlow<Set<String>>
    get() = cacheManager.changedKeys

  /**
   * Reads an operation from the store.
   *
   * The returned [ApolloResponse.data] has `null` values for any missing fields if their type is nullable, propagating up to their parent
   * otherwise. Missing fields have a corresponding [Error]
   * in [ApolloResponse.errors].
   *
   * @param operation the operation to read
   *
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
   * Reads a fragment from the store.
   *
   * @param fragment the fragment to read
   * @param cacheKey the root where to read the fragment data from
   *
   * @throws [com.apollographql.apollo.exception.CacheMissException] on cache miss
   * @throws [com.apollographql.apollo.exception.ApolloException] on other cache read errors
   *
   * @return the fragment data with optional headers from the [NormalizedCache]
   *
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
   * Writes an operation to the store.
   *
   * Call [publish] with the returned keys to notify any watchers.
   *
   * @param operation the operation to write
   * @param data the operation data to write
   * @param errors the operation errors to write
   * @return the changed field keys
   *
   * @see publish
   *
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
   * Writes a fragment to the store.
   *
   * Call [publish] with the returned keys to notify any watchers.
   *
   * @param fragment the fragment to write
   * @param cacheKey the root where to write the fragment data to
   * @param data the fragment data to write
   * @return the changed field keys
   *
   * @see publish
   *
   * @see CacheManager.writeOperation
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
   * Writes a fragment to the store.
   *
   * Call [publish] with the returned keys to notify any watchers.
   *
   * @param fragment the fragment to write
   * @param cacheKey the root where to write the fragment data to
   * @param data the fragment data to write
   * @return the changed field keys
   *
   * @see publish
   *
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
   * Writes an operation to the optimistic store.
   *
   * Optimistic updates must be enabled to use this method. To do so, pass `enableOptimisticUpdates = true` to the `CacheManager` constructor
   * or [normalizedCache] extension.
   *
   * Call [publish] with the returned keys to notify any watchers.
   *
   * @param operation the operation to write
   * @param data the operation data to write
   * @param mutationId a unique identifier for this optimistic update
   * @return the changed field keys
   *
   * @see publish
   *
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
   * Writes a fragment to the optimistic store.
   *
   * Optimistic updates must be enabled to use this method. To do so, pass `enableOptimisticUpdates = true` to the `CacheManager` constructor
   * or [normalizedCache] extension.
   *
   * Call [publish] with the returned keys to notify any watchers.
   *
   * @param fragment the fragment to write
   * @param cacheKey the root where to write the fragment data to
   * @param data the fragment data to write
   * @param mutationId a unique identifier for this optimistic update
   * @return the changed field keys
   *
   * @see publish
   *
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
   * Rollbacks optimistic updates.
   *
   * Optimistic updates must be enabled to use this method. To do so, pass `enableOptimisticUpdates = true` to the `CacheManager` constructor
   * or [normalizedCache] extension.
   *
   * Call [publish] with the returned keys to notify any watchers.
   *
   * @param mutationId the unique identifier of the optimistic update to rollback
   * @return the changed field keys
   *
   * @see publish
   *
   * @see CacheManager.rollbackOptimisticUpdates
   */
  suspend fun rollbackOptimisticUpdates(mutationId: Uuid): Set<String> = cacheManager.rollbackOptimisticUpdates(mutationId)

  /**
   * Clears all records.
   *
   * Call [publish] with [ALL_KEYS] to notify any watchers.
   *
   * @return `true` if all records were successfully removed, `false` otherwise
   *
   * @see CacheManager.clearAll
   */
  suspend fun clearAll(): Boolean = cacheManager.clearAll()

  /**
   * Removes a record by its key.
   *
   * Call [publish] with [ALL_KEYS] to notify any watchers.
   *
   * @param cacheKey the key of the record to remove
   * @param cascade whether referenced records should also be removed
   * @return `true` if the record was successfully removed, `false` otherwise
   *
   * @see CacheManager.remove
   */
  suspend fun remove(cacheKey: CacheKey, cascade: Boolean = true): Boolean = cacheManager.remove(cacheKey, cascade)

  /**
   * Removes a list of records by their keys.
   * This is an optimized version of [remove] for caches that can batch operations.
   *
   * Call [publish] with [ALL_KEYS] to notify any watchers.
   *
   * @param cacheKeys the keys of the records to remove
   * @param cascade whether referenced records should also be removed
   * @return the number of records that have been removed
   *
   * @see CacheManager.remove
   */
  suspend fun remove(cacheKeys: List<CacheKey>, cascade: Boolean = true): Int = cacheManager.remove(cacheKeys, cascade)

  /**
   * Trims the store if its size exceeds [maxSizeBytes]. The amount of data to remove is determined by [trimFactor].
   * The oldest records are removed according to their updated date.
   *
   * This may not be supported by all cache implementations (currently this is implemented by the SQL cache).
   *
   * @param maxSizeBytes the size of the cache in bytes above which the cache should be trimmed.
   * @param trimFactor the factor of the cache size to trim.
   * @return the cache size in bytes after trimming or -1 if the operation is not supported.
   *
   * @see CacheManager.trim
   */
  suspend fun trim(maxSizeBytes: Long, trimFactor: Float = 0.1f): Long = cacheManager.trim(maxSizeBytes, trimFactor)

  /**
   * Normalizes executable data to a map of [Record] keyed by [Record.key].
   *
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
   * Publishes a set of keys of record fields that have changed. This will notify watchers and any subscribers of [changedKeys].
   *
   * Pass [ALL_KEYS] to indicate that all records have changed, for instance after a [clearAll] operation.
   *
   * @see changedKeys
   * @see watch
   *
   * @param keys A set of keys of [Record] fields which have changed.
   *
   * @see CacheManager.publish
   */
  suspend fun publish(keys: Set<String>) = cacheManager.publish(keys)

  /**
   * Direct access to the cache.
   *
   * @param block a function that can access the cache.
   *
   * @see CacheManager.accessCache
   */
  suspend fun <R> accessCache(block: suspend (NormalizedCache) -> R): R = cacheManager.accessCache(block)

  /**
   * Dumps the content of the store for debugging purposes.
   *
   * @see CacheManager.dump
   */
  suspend fun dump(): Map<KClass<*>, Map<CacheKey, Record>> = cacheManager.dump()

  /**
   * Releases resources associated with this store.
   *
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
