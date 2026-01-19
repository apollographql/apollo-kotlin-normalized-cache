package com.apollographql.cache.normalized.internal

import com.apollographql.apollo.api.CompiledField
import com.apollographql.apollo.api.CompiledFragment
import com.apollographql.apollo.api.CompiledSelection
import com.apollographql.apollo.api.Error
import com.apollographql.apollo.api.Executable
import com.apollographql.apollo.exception.ApolloGraphQLException
import com.apollographql.apollo.exception.CacheMissException
import com.apollographql.cache.normalized.api.ApolloCacheHeaders
import com.apollographql.cache.normalized.api.CacheHeaders
import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.api.CacheResolver
import com.apollographql.cache.normalized.api.DataWithErrors
import com.apollographql.cache.normalized.api.FieldKeyGenerator
import com.apollographql.cache.normalized.api.ReadOnlyNormalizedCache
import com.apollographql.cache.normalized.api.Record
import com.apollographql.cache.normalized.api.ResolverContext
import com.apollographql.cache.normalized.api.isRootKey
import com.apollographql.cache.normalized.cacheMissException
import kotlin.jvm.JvmSuppressWildcards

/**
 * A resolver that solves the "N+1" problem by batching all SQL queries at a given depth.
 * It respects skip/include directives.
 */
internal class CacheBatchReader(
    private val cache: ReadOnlyNormalizedCache,
    private val rootKey: CacheKey,
    private val variables: Executable.Variables,
    private val cacheResolver: CacheResolver,
    private val cacheHeaders: CacheHeaders,
    private val rootSelections: List<CompiledSelection>,
    private val rootField: CompiledField,
    private val fieldKeyGenerator: FieldKeyGenerator,

    /**
     * If false, cache misses are returned inside the [DataWithErrors], otherwise, they throw [CacheMissException].
     */
    private val throwOnCacheMiss: Boolean,

    /**
     * If false, server errors are returned inside the [DataWithErrors], otherwise, they throw [ApolloGraphQLException].
     */
    private val serverErrorsAsCacheMisses: Boolean,
) {
  /**
   * @param key: the key of the record we need to fetch
   * @param path: the path where this pending reference needs to be inserted
   */
  class PendingReference(
      val key: CacheKey,
      val path: List<Any>,
      val fieldPath: List<CompiledField>,
      val selections: List<CompiledSelection>,
      val parentType: String,
  )

  /**
   * The objects read from the cache, as a `Map<String, Any?>` with only the fields that are selected and maybe some values changed.
   * Can also be an [Error] in case of a cache miss.
   * The key is the path to the object.
   */
  private val data = mutableMapOf<List<Any>, Any>()

  /**
   * True if at least one of the resolved fields is stale
   */
  private var isStale = false

  /**
   * True if at least one of the resolved fields is an Error, or if a cache miss happened
   */
  private var hasErrors = false

  private val pendingReferences = mutableListOf<PendingReference>()

  private class CollectState(val variables: Executable.Variables) {
    val fields = mutableListOf<CompiledField>()
  }

  private fun collect(selections: List<CompiledSelection>, parentType: String, typename: String?, state: CollectState) {
    selections.forEach { compiledSelection ->
      when (compiledSelection) {
        is CompiledField -> {
          state.fields.add(compiledSelection)
        }

        is CompiledFragment -> {
          if ((typename in compiledSelection.possibleTypes || compiledSelection.typeCondition == parentType) && !compiledSelection.shouldSkip(state.variables.valueMap)) {
            collect(compiledSelection.selections, parentType, typename, state)
          }
        }
      }
    }
  }

  private fun collectAndMergeSameDirectives(
      selections: List<CompiledSelection>,
      parentType: String,
      variables: Executable.Variables,
      typename: String?,
  ): List<CompiledField> {
    val state = CollectState(variables)
    collect(selections, parentType, typename, state)
    return state.fields.groupBy { (it.responseName) to it.condition }.values.map { fields ->
      fields.first().newBuilder().selections(fields.flatMap { it.selections }).build()
    }
  }

  suspend fun collectData(): CacheBatchReaderData {
    pendingReferences.add(
        PendingReference(
            key = rootKey,
            selections = rootSelections,
            parentType = rootField.type.rawType().name,
            path = emptyList(),
            fieldPath = listOf(rootField),
        ),
    )

    while (pendingReferences.isNotEmpty()) {
      val records: Map<CacheKey, Record> = cache.loadRecords(pendingReferences.map { it.key }, cacheHeaders)
          .also { records ->
            if (!hasErrors) {
              val serverError = records.firstNotNullOfOrNull { it.values.firstNotNullOfOrNull { fieldValue -> fieldValue.firstError() } }
              if (serverError != null) {
                if (serverErrorsAsCacheMisses) {
                  throw ApolloGraphQLException(serverError)
                } else {
                  hasErrors = true
                }
              }
            }
          }
          .associateBy { it.key }
      val copy = pendingReferences.toList()
      pendingReferences.clear()
      copy.forEach { pendingReference ->
        var record = records[pendingReference.key]
        if (record == null) {
          if (pendingReference.key.isRootKey()) {
            // This happens the very first time we read the cache
            record = Record(pendingReference.key, emptyMap())
          } else {
            if (throwOnCacheMiss) {
              throw CacheMissException(pendingReference.key.keyToString())
            } else {
              data[pendingReference.path] =
                cacheMissError(CacheMissException(key = pendingReference.key.keyToString(), fieldName = null, stale = false), path = pendingReference.path)
              hasErrors = true
              return@forEach
            }
          }
        }

        val collectedFields =
          collectAndMergeSameDirectives(pendingReference.selections, pendingReference.parentType, variables, record["__typename"] as? String)

        val map = collectedFields.mapNotNull {
          if (it.shouldSkip(variables.valueMap)) {
            return@mapNotNull null
          }

          val value = try {
            cacheResolver.resolveField(
                ResolverContext(
                    field = it,
                    variables = variables,
                    parent = record,
                    parentKey = record.key,
                    parentType = pendingReference.parentType,
                    cacheHeaders = cacheHeaders,
                    fieldKeyGenerator = fieldKeyGenerator,
                    path = pendingReference.fieldPath + it,
                ),
            ).unwrap()
          } catch (e: CacheMissException) {
            if (e.stale) isStale = true
            if (throwOnCacheMiss) {
              throw e
            } else {
              hasErrors = true
              cacheMissError(e, pendingReference.path + it.responseName)
            }
          }
          value.registerCacheKeys(pendingReference.path + it.responseName, pendingReference.fieldPath + it, it.selections, it.type.rawType().name)

          it.responseName to value
        }.toMap()

        data[pendingReference.path] = map
      }
    }

    return CacheBatchReaderData(
        data = data,
        cacheHeaders = CacheHeaders.Builder().apply { if (isStale) addHeader(ApolloCacheHeaders.STALE, "true") }.build(),
        hasErrors = hasErrors,
    )
  }

  private fun Any?.unwrap(): Any? {
    return when (this) {
      is CacheResolver.ResolvedValue -> {
        if (cacheHeaders.headerValue(ApolloCacheHeaders.STALE) == "true") {
          isStale = true
        }
        this.value
      }

      else -> {
        this
      }
    }
  }

  /**
   * The path leading to this value
   */
  private fun Any?.registerCacheKeys(
      path: List<Any>,
      fieldPath: List<CompiledField>,
      selections: List<CompiledSelection>,
      parentType: String,
  ) {
    when (this) {
      is CacheKey -> {
        pendingReferences.add(
            PendingReference(
                key = this,
                selections = selections,
                parentType = parentType,
                path = path,
                fieldPath = fieldPath,
            ),
        )
      }

      is List<*> -> {
        forEachIndexed { index, value ->
          value.registerCacheKeys(path + index, fieldPath, selections, parentType)
        }
      }

      is Map<*, *> -> {
        @Suppress("UNCHECKED_CAST")
        this as Map<String, @JvmSuppressWildcards Any?>
        val collectedFields = collectAndMergeSameDirectives(selections, parentType, variables, get("__typename") as? String)
        collectedFields.mapNotNull {
          if (it.shouldSkip(variables.valueMap)) {
            return@mapNotNull null
          }

          val value = try {
            cacheResolver.resolveField(
                ResolverContext(
                    field = it,
                    variables = variables,
                    parent = this,
                    parentKey = CacheKey(""),
                    parentType = parentType,
                    cacheHeaders = cacheHeaders,
                    fieldKeyGenerator = fieldKeyGenerator,
                    path = fieldPath + it,
                ),
            ).unwrap()
          } catch (e: CacheMissException) {
            if (e.stale) isStale = true
            if (throwOnCacheMiss) {
              throw e
            } else {
              hasErrors = true
              cacheMissError(e, path + it.responseName)
            }
          }
          value.registerCacheKeys(path + it.responseName, fieldPath + it, it.selections, it.type.rawType().name)
        }
      }
    }
  }

  internal class CacheBatchReaderData(
      private val data: Map<List<Any>, Any>,
      val cacheHeaders: CacheHeaders,
      val hasErrors: Boolean,
  ) {
    @Suppress("UNCHECKED_CAST")
    internal fun toMap(withErrors: Boolean = true): DataWithErrors {
      return data[emptyList()].replaceCacheKeys(emptyList(), withErrors) as DataWithErrors
    }

    private fun Any?.replaceCacheKeys(path: List<Any>, withErrors: Boolean): Any? {
      return when (this) {
        is CacheKey -> {
          data[path].replaceCacheKeys(path, withErrors)
        }

        is List<*> -> {
          mapIndexed { index, src ->
            src.replaceCacheKeys(path + index, withErrors)
          }
        }

        is Map<*, *> -> {
          // This will traverse Map custom scalars but this is ok as it shouldn't contain any CacheKey
          mapValues {
            it.value.replaceCacheKeys(path + (it.key as String), withErrors)
          }
        }

        is Error -> {
          if (withErrors) {
            this
          } else {
            null
          }
        }

        else -> {
          // Scalar value
          this
        }
      }
    }
  }

  private fun cacheMissError(exception: CacheMissException, path: List<Any>): Error {
    val message = if (exception.fieldName == null) {
      "Object '${exception.key}' not found in the cache"
    } else {
      if (exception.stale) {
        "Field '${exception.fieldName}' on object '${exception.key}' is stale in the cache"
      } else {
        "Object '${exception.key}' has no field named '${exception.fieldName}' in the cache"
      }
    }
    return Error.Builder(message)
        .path(path = path)
        .cacheMissException(exception)
        .build()
  }

  @Suppress("UNCHECKED_CAST")
  internal fun Any?.firstError(): Error? {
    val queue = ArrayDeque<Any?>()
    queue.add(this)
    while (queue.isNotEmpty()) {
      when (val current = queue.removeFirst()) {
        is Error -> return current
        is List<*> -> queue.addAll(current)
        // Embedded fields can be represented as Maps
        is Map<*, *> -> queue.addAll(current.values)
      }
    }
    return null
  }
}
