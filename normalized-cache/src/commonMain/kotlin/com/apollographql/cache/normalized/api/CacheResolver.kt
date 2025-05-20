package com.apollographql.cache.normalized.api

import com.apollographql.apollo.api.CompiledField
import com.apollographql.apollo.api.CompiledListType
import com.apollographql.apollo.api.CompiledNotNullType
import com.apollographql.apollo.api.Executable
import com.apollographql.apollo.api.MutableExecutionOptions
import com.apollographql.apollo.exception.CacheMissException
import com.apollographql.apollo.mpp.currentTimeMillis
import com.apollographql.cache.normalized.api.CacheResolver.ResolvedValue
import com.apollographql.cache.normalized.maxStale
import com.apollographql.cache.normalized.storeExpirationDate
import com.apollographql.cache.normalized.storeReceivedDate
import kotlin.jvm.JvmSuppressWildcards

/**
 * Controls how fields are resolved from the cache.
 */
interface CacheResolver {
  /**
   * Resolves a field from the cache. Called when reading from the cache, usually before a network request.
   * - takes a GraphQL field and operation variables as input and generates data for this field
   * - this data can be a CacheKey for objects but it can also be any other data if needed. In that respect,
   * it's closer to a resolver as might be found in a GraphQL server
   * - used before a network request
   * - used when reading the cache
   *
   * It can be used to map field arguments to [CacheKey]:
   *
   * ```
   * {
   *   user(id: "1"}) {
   *     id
   *     firstName
   *     lastName
   *   }
   * }
   * ```
   *
   * ```
   * override fun resolveField(context: ResolverContext): Any? {
   *   val id = context.field.resolveArgument("id", context.variables)?.toString()
   *   if (id != null) {
   *     return CacheKey(id)
   *   }
   *
   *   return super.resolveField(context)
   * }
   * ```
   *
   * The simple example above isn't very representative as most of the time `@fieldPolicy` can express simple argument mappings in a more
   * concise way but still demonstrates how [resolveField] works.
   *
   * [resolveField] can also be generalized to return any value:
   *
   * ```
   * override fun resolveField(context: ResolverContext): Any? {
   *   if (context.field.name == "name") {
   *     // Every "name" field will return "JohnDoe" now!
   *     return "JohnDoe"
   *   }
   *
   *   return super.resolveField(context)
   * }
   * ```
   *
   * See also `@fieldPolicy`
   * See also [CacheKeyGenerator]
   *
   * @param context the field to resolve and associated information to resolve it
   *
   * @return a value that can go in a [Record]. No type checking is done. It is the responsibility of implementations to return the correct
   * type. The value can be wrapped in a [ResolvedValue] to provide additional information.
   */
  fun resolveField(context: ResolverContext): Any?

  class ResolvedValue(
      val value: Any?,
      val cacheHeaders: CacheHeaders,
  )
}

class ResolverContext(
    /**
     * The field to resolve
     */
    val field: CompiledField,

    /**
     * The variables of the current operation
     */
    val variables: Executable.Variables,

    /**
     * The parent object as a map. It can contain the same values as [Record]. Especially, nested objects will be represented
     * by [CacheKey]
     */
    val parent: Map<String, @JvmSuppressWildcards Any?>,

    /**
     * The key of the parent. Mainly used for debugging
     */
    val parentKey: CacheKey,

    /**
     * The type of the parent
     */
    val parentType: String,

    /**
     * The cache headers used to pass arbitrary information to the resolver
     */
    val cacheHeaders: CacheHeaders,

    /**
     * The [FieldKeyGenerator] to use to generate field keys
     */
    val fieldKeyGenerator: FieldKeyGenerator,

    /**
     * The path of the field to resolve.
     * The first element is the root object, the last element is [field].
     */
    val path: List<CompiledField>,
)

fun ResolverContext.getFieldKey(): String {
  return fieldKeyGenerator.getFieldKey(FieldKeyContext(parentType, field, variables))
}

/**
 * A cache resolver that uses the parent to resolve fields.
 */
object DefaultCacheResolver : CacheResolver {
  override fun resolveField(context: ResolverContext): Any? {
    val fieldKey = context.getFieldKey()
    if (!context.parent.containsKey(fieldKey)) {
      throw CacheMissException(context.parentKey.keyToString(), fieldKey)
    }

    return context.parent[fieldKey]
  }
}

/**
 * A cache resolver that raises a cache miss if the field's received date is older than its max age
 * (configurable via [maxAgeProvider]) or if its expiration date has passed.
 *
 * Received dates are stored by calling `storeReceivedDate(true)` on your `ApolloClient`.
 *
 * Expiration dates are stored by calling `storeExpirationDate(true)` on your `ApolloClient`.
 *
 * A maximum staleness can be configured via the [ApolloCacheHeaders.MAX_STALE] cache header.
 *
 * @param maxAgeProvider the provider for the max age of fields
 * @param delegateResolver the resolver to delegate to for non-stale fields, by default [FieldPolicyCacheResolver]
 *
 * @see MutableExecutionOptions.storeReceivedDate
 * @see MutableExecutionOptions.storeExpirationDate
 * @see MutableExecutionOptions.maxStale
 */
class CacheControlCacheResolver(
    private val maxAgeProvider: MaxAgeProvider,
    private val delegateResolver: CacheResolver = FieldPolicyCacheResolver(keyScope = CacheKey.Scope.TYPE),
) : CacheResolver {
  /**
   * Creates a new [CacheControlCacheResolver] with no max ages. Use this constructor if you want to consider only the expiration dates.
   */
  constructor(
      delegateResolver: CacheResolver = FieldPolicyCacheResolver(keyScope = CacheKey.Scope.TYPE),
  ) : this(
      maxAgeProvider = DefaultMaxAgeProvider,
      delegateResolver = delegateResolver,
  )

  override fun resolveField(context: ResolverContext): Any? {
    var isStale = false
    if (context.parent is Record) {
      val field = context.field
      // Consider the client controlled max age
      val receivedDate = context.parent.receivedDate(field.name)
      if (receivedDate != null) {
        val currentDate = currentTimeMillis() / 1000
        val age = currentDate - receivedDate
        val fieldPath = context.path.map {
          it.toMaxAgeField()
        }
        val maxAge = maxAgeProvider.getMaxAge(MaxAgeContext(fieldPath)).inWholeSeconds
        val staleDuration = age - maxAge
        val maxStale = context.cacheHeaders.headerValue(ApolloCacheHeaders.MAX_STALE)?.toLongOrNull() ?: 0L
        if (staleDuration >= maxStale) {
          throw CacheMissException(
              key = context.parentKey.keyToString(),
              fieldName = context.getFieldKey(),
              stale = true
          )
        }
        if (staleDuration >= 0) isStale = true
      }

      // Consider the server controlled max age
      val expirationDate = context.parent.expirationDate(field.name)
      if (expirationDate != null) {
        val currentDate = currentTimeMillis() / 1000
        val staleDuration = currentDate - expirationDate
        val maxStale = context.cacheHeaders.headerValue(ApolloCacheHeaders.MAX_STALE)?.toLongOrNull() ?: 0L
        if (staleDuration >= maxStale) {
          throw CacheMissException(
              key = context.parentKey.keyToString(),
              fieldName = context.getFieldKey(),
              stale = true
          )
        }
        if (staleDuration >= 0) isStale = true
      }
    }

    val value = delegateResolver.resolveField(context)
    return if (isStale) {
      ResolvedValue(
          value = value,
          cacheHeaders = CacheHeaders.Builder().addHeader(ApolloCacheHeaders.STALE, "true").build(),
      )
    } else {
      value
    }
  }
}

/**
 * A cache resolver that uses `@fieldPolicy` directives to resolve fields and delegates to [DefaultCacheResolver] otherwise.
 *
 * Note: this namespaces the ids with the **schema** type, will lead to cache misses for:
 * - unions
 * - interfaces that have a `@typePolicy` on subtypes.
 *
 * If the ids are unique across the whole service, use `FieldPolicyCacheResolver(keyScope = CacheKey.Scope.SERVICE)`. Otherwise there is
 * no way to resolve the cache key automatically for those cases.
 */
@Deprecated("Use FieldPolicyCacheResolver(keyScope) instead")
val FieldPolicyCacheResolver: CacheResolver = FieldPolicyCacheResolver(keyScope = CacheKey.Scope.TYPE)

/**
 * A cache resolver that uses `@fieldPolicy` directives to resolve fields and delegates to [DefaultCacheResolver] otherwise.
 *
 * Note: using a [CacheKey.Scope.TYPE] `keyScope` namespaces the ids with the **schema** type, which will lead to cache misses for:
 * - unions
 * - interfaces that have a `@typePolicy` on subtypes.
 *
 * If the ids are unique across the whole service, use [CacheKey.Scope.SERVICE]. Otherwise there is no way to resolve the cache keys
 * automatically for those cases.
 *
 * @param keyScope the scope of the computed cache keys. Use [CacheKey.Scope.TYPE] to namespace the keys by the schema type name, or
 * [CacheKey.Scope.SERVICE] if the ids are unique across the whole service.
 */
fun FieldPolicyCacheResolver(
    keyScope: CacheKey.Scope = CacheKey.Scope.TYPE,
) = object : CacheResolver {
  override fun resolveField(context: ResolverContext): Any? {
    val keyArgsValues = context.field.argumentValues(context.variables) { it.definition.isKey }.values
    if (keyArgsValues.isEmpty()) {
      return DefaultCacheResolver.resolveField(context)
    }
    var type = context.field.type
    if (type is CompiledNotNullType) {
      type = type.ofType
    }
    if (type is CompiledListType) {
      // Only support flat lists
      if (type.ofType !is CompiledListType && !(type.ofType is CompiledNotNullType && (type.ofType as CompiledNotNullType).ofType is CompiledListType)) {
        // Only support single key argument which is a flat list
        if (keyArgsValues.size == 1) {
          val keyArgsValue = keyArgsValues.first() as? List<*>
          if (keyArgsValue != null && keyArgsValue.firstOrNull() !is List<*>) {
            return keyArgsValue.map {
              if (keyScope == CacheKey.Scope.TYPE) {
                CacheKey(type.rawType().name, it.toString())
              } else {
                CacheKey(it.toString())
              }
            }
          }
        }
      }
    }
    return if (keyScope == CacheKey.Scope.TYPE) {
      CacheKey(type.rawType().name, keyArgsValues.map { it.toString() })
    } else {
      CacheKey(keyArgsValues.map { it.toString() })
    }
  }
}
