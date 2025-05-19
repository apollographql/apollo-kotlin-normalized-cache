package com.apollographql.cache.normalized.api

import com.apollographql.apollo.api.CompiledListType
import com.apollographql.apollo.api.CompiledNamedType
import com.apollographql.apollo.api.CompiledNotNullType
import com.apollographql.apollo.api.isComposite

/**
 * A [CacheResolver] that resolves objects and list of objects and falls back to the default resolver for scalar fields.
 * It is intended to simplify the usage of [CacheResolver] when no special handling is needed for scalar fields.
 *
 * Override [cacheKeyForField] to compute a cache key for a field of composite type.
 * Override [listOfCacheKeysForField] to compute a list of cache keys for a field of 'list-of-composite' type.
 *
 * For simplicity, this only handles one level of lists. Implement [CacheResolver] if you need arbitrary nested lists of objects.
 */
abstract class CacheKeyResolver : CacheResolver {
  /**
   * Returns the computed cache key for a composite field.
   *
   * If the field is of object type, you can get the object typename with `field.type.rawType().name`.
   * If the field is of interface or union type, the concrete object typename is not predictable and the returned [CacheKey] must be unique
   * in the whole schema as it cannot be namespaced by the typename anymore.
   *
   * If the returned [CacheKey] is null, the resolver will use the default handling and use any previously cached value.
   */
  abstract fun cacheKeyForField(context: ResolverContext): CacheKey?

  /**
   * For a field that contains a list of objects, [listOfCacheKeysForField] returns a list of [CacheKey]s where each [CacheKey] identifies an object.
   *
   * If the field is of object type, you can get the object typename with `field.type.rawType().name`.
   * If the field is of interface or union type, the concrete object typename is not predictable and the returned [CacheKey] must be unique
   * in the whole schema as it cannot be namespaced by the typename anymore.
   *
   * If an individual [CacheKey] is null, the resulting object will be null in the response.
   * If the returned list of [CacheKey]s is null, the resolver will use the default handling and use any previously cached value.
   */
  open fun listOfCacheKeysForField(context: ResolverContext): List<CacheKey?>? = null

  final override fun resolveField(context: ResolverContext): Any? {
    var type = context.field.type
    if (type is CompiledNotNullType) {
      type = type.ofType
    }
    if (type is CompiledNamedType && type.isComposite()) {
      val result = cacheKeyForField(context)
      if (result != null) {
        return result
      }
    }

    if (type is CompiledListType) {
      type = type.ofType
      if (type is CompiledNotNullType) {
        type = type.ofType
      }
      if (type is CompiledNamedType && type.isComposite()) {
        val result = listOfCacheKeysForField(context)
        if (result != null) {
          return result
        }
      }
    }

    return DefaultCacheResolver.resolveField(context)
  }
}

/**
 * A simple [CacheKeyResolver] that uses the id/ids argument, if present, to compute the cache key.
 * The name of the id arguments can be provided (by default "id" for objects and "ids" for lists).
 * If several names are provided, the first present one is used.
 * Only one level of list is supported - implement [CacheResolver] if you need arbitrary nested lists of objects.
 *
 * @param idFields possible names of the argument containing the id for objects
 * @param idListFields possible names of the argument containing the ids for lists
 * @param keyScope the scope of the computed cache keys. Use [CacheKey.Scope.TYPE] to namespace the keys by the schema type name, or
 * [CacheKey.Scope.SERVICE] if the ids are unique across the whole service.
 *
 * @see IdCacheKeyGenerator
 */
class IdCacheKeyResolver(
    private val idFields: List<String> = listOf("id"),
    private val idListFields: List<String> = listOf("ids"),
    private val keyScope: CacheKey.Scope = CacheKey.Scope.TYPE,
) : CacheKeyResolver() {
  override fun cacheKeyForField(context: ResolverContext): CacheKey? {
    val fieldKey = context.getFieldKey()
    if (context.parent[fieldKey] != null) {
      return null
    }
    val id = idFields.firstNotNullOfOrNull { context.field.argumentValue(it, context.variables).getOrNull()?.toString() } ?: return null
    return if (keyScope == CacheKey.Scope.TYPE) {
      CacheKey(context.field.type.rawType().name, id)
    } else {
      CacheKey(id)
    }
  }

  override fun listOfCacheKeysForField(context: ResolverContext): List<CacheKey?>? {
    val fieldKey = context.getFieldKey()
    if (context.parent[fieldKey] != null) {
      return null
    }
    val ids = idListFields.firstNotNullOfOrNull { context.field.argumentValue(it, context.variables).getOrNull() as? List<*> }
        ?: return null
    return ids.map { id ->
      id?.toString()?.let {
        if (keyScope == CacheKey.Scope.TYPE) {
          CacheKey(context.field.type.rawType().name, it)
        } else {
          CacheKey(it)
        }
      }
    }
  }
}
