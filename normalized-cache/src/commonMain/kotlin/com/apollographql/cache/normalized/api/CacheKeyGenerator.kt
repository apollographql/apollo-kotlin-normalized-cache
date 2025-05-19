package com.apollographql.cache.normalized.api

import com.apollographql.apollo.api.CompiledField
import com.apollographql.apollo.api.Executable
import com.apollographql.apollo.api.keyFields

/**
 * An [CacheKeyGenerator] is responsible for finding an id for a given object
 * - takes Json data as input and returns a unique id for an object
 * - is used after a network request
 * - is used during normalization when writing to the cache
 *
 * See also `@typePolicy`
 * See also [CacheResolver]
 */
interface CacheKeyGenerator {
  /**
   * Returns a [CacheKey] for the given object or null if the object doesn't have an id
   *
   * @param obj a [Map] representing the object. The values in the map can have the same types as the ones
   * in [Record]
   * @param context the context in which the object is normalized. In most use cases, the id should not depend on the normalization
   * context. Only use for advanced use cases.
   */
  fun cacheKeyForObject(obj: Map<String, Any?>, context: CacheKeyGeneratorContext): CacheKey?
}

/**
 * The context in which an object is normalized.
 *
 * @param field the field representing the object or for lists, the field representing the list. `field.type` is not
 * always the type of the object. Especially, it can be any combination of [com.apollographql.apollo.api.CompiledNotNullType]
 * and [com.apollographql.apollo.api.CompiledListType].
 * Use `field.type.rawType()` to access the type of the object. For interface fields, it will be the interface type,
 * not concrete types.
 * @param variables the variables used in the operation where the object is normalized.
 */
class CacheKeyGeneratorContext(
    val field: CompiledField,
    val variables: Executable.Variables,
)

/**
 * A [CacheKeyGenerator] that uses the `@typePolicy` directive to compute the cache key.
 *
 * Note: this uses the key fields of the **schema** type and therefore can't generate cache keys for:
 * - unions
 * - interfaces that have a `@typePolicy` on subtypes.
 *
 * For those cases, prefer `fun TypePolicyCacheKeyGenerator(typePolicies, keyScope)`, which uses the concrete type (`__typename`) instead.
 */
@Deprecated("Use TypePolicyCacheKeyGenerator(typePolicies, keyScope) instead")
object TypePolicyCacheKeyGenerator : CacheKeyGenerator {
  override fun cacheKeyForObject(obj: Map<String, Any?>, context: CacheKeyGeneratorContext): CacheKey? {
    val keyFields = context.field.type.rawType().keyFields()
    return if (keyFields.isNotEmpty()) {
      CacheKey(obj["__typename"].toString(), keyFields.map { obj[it].toString() })
    } else {
      null
    }
  }
}

/**
 * A [CacheKeyGenerator] that uses the `@typePolicy` directive to compute the cache key.
 *
 * This uses the field's concrete type (`__typename`).
 *
 * @param typePolicies the type policies as declared in the schema via `@typePolicy`.
 * @param keyScope the scope of the generated cache keys. Use [CacheKey.Scope.TYPE] to namespace the keys by the concrete type name, or
 * [CacheKey.Scope.SERVICE] if the ids are unique across the whole service.
 */
fun TypePolicyCacheKeyGenerator(
    typePolicies: Map<String, TypePolicy>,
    keyScope: CacheKey.Scope = CacheKey.Scope.TYPE,
) = object : CacheKeyGenerator {
  override fun cacheKeyForObject(obj: Map<String, Any?>, context: CacheKeyGeneratorContext): CacheKey? {
    val typeName = obj["__typename"].toString()
    val typePolicy = typePolicies[typeName]
    // If a type is unknown at build type, it might be an interface that has a type policy
        ?: typePolicies[context.field.type.rawType().name]
        ?: return null
    return if (keyScope == CacheKey.Scope.TYPE) {
      CacheKey(typeName, typePolicy.keyFields.map { obj[it].toString() })
    } else {
      CacheKey(typePolicy.keyFields.map { obj[it].toString() })
    }
  }
}

/**
 * A [CacheKeyGenerator] that uses the given id fields to compute the cache key.
 * If the id field(s) is/are missing, the object is considered to not have an id.
 *
 * @param idFields the possible names of the fields to use as id. The first present one is used.
 * @param keyScope the scope of the generated cache keys. Use [CacheKey.Scope.TYPE] to namespace the keys by the concrete type name, or
 * [CacheKey.Scope.SERVICE] if the ids are unique across the whole service.
 *
 * @see IdCacheKeyResolver
 */
class IdCacheKeyGenerator(
    private vararg val idFields: String = arrayOf("id"),
    private val keyScope: CacheKey.Scope = CacheKey.Scope.TYPE,
) : CacheKeyGenerator {
  override fun cacheKeyForObject(obj: Map<String, Any?>, context: CacheKeyGeneratorContext): CacheKey? {
    val values = idFields.map {
      (obj[it] ?: return null).toString()
    }
    return if (keyScope == CacheKey.Scope.TYPE) {
      CacheKey(obj["__typename"].toString(), values)
    } else {
      CacheKey(values)
    }
  }
}
