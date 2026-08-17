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
 * A node in the tree of response paths.
 *
 * The paths of a response all share prefixes, so they are interned into a tree rather than held as
 * lists: appending a segment is a lookup on the parent node instead of a copy of the whole path, and
 * keying a map on a path hashes an identity instead of walking its segments. Every path is also asked
 * for more than once - the reader builds one per field of every object it reads, then walks them all
 * again to assemble the result - and interning hands back the node built the first time instead of
 * rebuilding the list.
 *
 * Two paths are the same node if and only if they have the same segments, which is what lets identity
 * stand in for equality.
 *
 * [append] mutates the tree, so a tree belongs to the single read that builds it.
 */
internal class ResponsePath private constructor(
    private val parent: ResponsePath?,
    private val segment: Any?,
) {
  private var children: MutableMap<Any, ResponsePath>? = null

  fun append(segment: Any): ResponsePath {
    var children = this.children
    if (children == null) {
      // Only composite fields ever get children, so leaves carry no map at all.
      children = mutableMapOf()
      this.children = children
    }
    var child = children[segment]
    if (child == null) {
      child = ResponsePath(parent = this, segment = segment)
      children[segment] = child
    }
    return child
  }

  /**
   * This path as a list of segments, root first. Only needed to fill in the `path` of the errors
   * reported for cache misses.
   */
  fun asList(): List<Any> {
    val segments = mutableListOf<Any>()
    var node: ResponsePath = this
    while (true) {
      val parent = node.parent ?: break
      segments.add(node.segment!!)
      node = parent
    }
    segments.reverse()
    return segments
  }

  override fun toString(): String = asList().joinToString(".")

  companion object {
    fun root(): ResponsePath = ResponsePath(parent = null, segment = null)
  }
}

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
     * If true, cache misses throw [CacheMissException], otherwise they are returned inside the [DataWithErrors].
     */
    private val cacheMissesAsException: Boolean,

    /**
     * If true, cached server errors throw [ApolloGraphQLException], otherwise they are returned inside the [DataWithErrors].
     */
    private val serverErrorsAsException: Boolean,
) {
  /**
   * @param key: the key of the record we need to fetch
   * @param path: the path where this pending reference needs to be inserted
   */
  class PendingReference(
      val key: CacheKey,
      val path: ResponsePath,
      val fieldPath: List<CompiledField>,
      val selections: List<CompiledSelection>,
      val parentType: String,
  )

  private val rootPath = ResponsePath.root()

  /**
   * The objects read from the cache, as a `Map<String, Any?>` with only the fields that are selected and maybe some values changed.
   * Can also be an [Error] in case of a cache miss.
   * The key is the path to the object.
   */
  private val data = mutableMapOf<ResponsePath, Any>()

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
    val fields = state.fields
    // Optimization: no need to merge when every field has its own response name.
    val responseNames = HashSet<String>()
    var hasSameResponseName = false
    for (field in fields) {
      if (!responseNames.add(field.responseName)) {
        hasSameResponseName = true
        break
      }
    }
    if (!hasSameResponseName) {
      return fields
    }
    return fields.groupBy { (it.responseName) to it.condition }.values.map { sameDirectives ->
      sameDirectives.first().newBuilder().selections(sameDirectives.flatMap { it.selections }).build()
    }
  }

  suspend fun collectData(): CacheBatchReaderData {
    pendingReferences.add(
        PendingReference(
            key = rootKey,
            selections = rootSelections,
            parentType = rootField.type.rawType().name,
            path = rootPath,
            fieldPath = listOf(rootField),
        ),
    )

    while (pendingReferences.isNotEmpty()) {
      val records: Map<CacheKey, Record> = cache.loadRecords(pendingReferences.map { it.key }, cacheHeaders)
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
            if (cacheMissesAsException) {
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

          val valuePath = pendingReference.path.append(it.responseName)
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
            if (cacheMissesAsException) {
              throw e
            } else {
              hasErrors = true
              cacheMissError(e, valuePath)
            }
          }.also { value ->
            if (!hasErrors) {
              val serverError = value.firstError()
              if (serverError != null) {
                if (serverErrorsAsException) {
                  throw ApolloGraphQLException(serverError)
                } else {
                  hasErrors = true
                }
              }
            }
          }
          value.registerCacheKeys(valuePath, pendingReference.fieldPath + it, it.selections, it.type.rawType().name)

          it.responseName to value
        }.toMap()

        if (data.contains(pendingReference.path) && data[pendingReference.path] is Map<*, *>) {
          /**
           * In the presence of include directives, we might already have read some data.
           * In that case, we need to merge them.
           *
           * Note1: if a key is present in both the existing and the new data, we expect the
           * value to be the same.
           * Note2: we're not deep merging because the reader reads normalized records. The only
           * case where there can be a nested map is scalar, and we expect the values to be the
           * same as in Note1.
           * See https://github.com/apollographql/apollo-kotlin/issues/6901
           */
          data[pendingReference.path] = data[pendingReference.path] as Map<*, *> + map
        } else {
          data[pendingReference.path] = map
        }
      }
    }

    return CacheBatchReaderData(
        data = data,
        rootPath = rootPath,
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
      path: ResponsePath,
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
          value.registerCacheKeys(path.append(index), fieldPath, selections, parentType)
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

          val valuePath = path.append(it.responseName)
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
            if (cacheMissesAsException) {
              throw e
            } else {
              hasErrors = true
              cacheMissError(e, valuePath)
            }
          }
          value.registerCacheKeys(valuePath, fieldPath + it, it.selections, it.type.rawType().name)
        }
      }
    }
  }

  internal class CacheBatchReaderData(
      private val data: Map<ResponsePath, Any>,
      private val rootPath: ResponsePath,
      val cacheHeaders: CacheHeaders,
      val hasErrors: Boolean,
  ) {
    @Suppress("UNCHECKED_CAST")
    internal fun toMap(withErrors: Boolean = true): DataWithErrors {
      return data[rootPath].replaceCacheKeys(rootPath, withErrors) as DataWithErrors
    }

    private fun Any?.replaceCacheKeys(path: ResponsePath, withErrors: Boolean): Any? {
      return when (this) {
        is CacheKey -> {
          data[path].replaceCacheKeys(path, withErrors)
        }

        is List<*> -> {
          mapIndexed { index, src ->
            src.replaceCacheKeys(path.append(index), withErrors)
          }
        }

        is Map<*, *> -> {
          // This will traverse Map custom scalars but this is ok as it shouldn't contain any CacheKey
          mapValues {
            it.value.replaceCacheKeys(path.append(it.key as String), withErrors)
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

  private fun cacheMissError(exception: CacheMissException, path: ResponsePath): Error {
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
        .path(path = path.asList())
        .cacheMissException(exception)
        .build()
  }

  /**
   * The first [Error] in this value, or null if it holds none.
   *
   * Called for every field read, where the overwhelming majority of values are scalars holding no
   * error at all, so it recurses rather than allocating a work queue to walk them with.
   */
  internal fun Any?.firstError(): Error? {
    when (this) {
      is Error -> return this
      is List<*> -> {
        for (item in this) {
          item.firstError()?.let { return it }
        }
      }
      // Embedded fields can be represented as Maps
      is Map<*, *> -> {
        for (value in values) {
          value.firstError()?.let { return it }
        }
      }
    }
    return null
  }
}
