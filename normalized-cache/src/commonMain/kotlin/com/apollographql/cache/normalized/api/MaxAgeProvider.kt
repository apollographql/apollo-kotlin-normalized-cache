package com.apollographql.cache.normalized.api

import com.apollographql.apollo.api.CompiledField
import com.apollographql.apollo.api.InterfaceType
import com.apollographql.apollo.api.ObjectType
import com.apollographql.apollo.api.isComposite
import kotlin.time.Duration

interface MaxAgeProvider {
  /**
   * Returns the max age for the given field.
   */
  fun getMaxAge(maxAgeContext: MaxAgeContext): Duration
}

class MaxAgeContext(
    /**
     * The path of the field to get the max age of.
     * The first element is the root object, the last element is the field to get the max age of.
     */
    val fieldPath: List<Field>,
) {
  class Field(
      val name: String,
      val type: Type,
  )

  class Type(
      val name: String,
      val isComposite: Boolean,
      val implements: List<Type>,
  )
}

/**
 * A provider that returns a single max age for all types.
 */
class GlobalMaxAgeProvider(internal val maxAge: Duration) : MaxAgeProvider {
  override fun getMaxAge(maxAgeContext: MaxAgeContext): Duration = maxAge
}

val DefaultMaxAgeProvider: MaxAgeProvider = GlobalMaxAgeProvider(Duration.INFINITE)

sealed interface MaxAge {
  class Duration(val duration: kotlin.time.Duration) : MaxAge
  data object Inherit : MaxAge
}

/**
 * A provider that returns a max age based on [schema coordinates](https://github.com/graphql/graphql-spec/pull/794).
 * The given coordinates must be object/interface/union (e.g. `MyType`) or field (e.g. `MyType.myField`) coordinates.
 *
 * The max age of a field is determined as follows:
 * - If the field has a [MaxAge.Duration] max age, return it.
 * - Else, if the field has a [MaxAge.Inherit] max age, return the max age of the parent field.
 * - Else, if the field's type has a [MaxAge.Duration] max age, return it.
 * - Else, if the field's type has a [MaxAge.Inherit] max age, return the max age of the parent field.
 * - Else, if the field is a root field, or the field's type is composite, return the default max age.
 * - Else, return the max age of the parent field.
 *
 * Then the lowest of the field's max age and its parent field's max age is returned.
 */
class SchemaCoordinatesMaxAgeProvider(
    private val maxAges: Map<String, MaxAge>,
    private val defaultMaxAge: Duration,
) : MaxAgeProvider {
  override fun getMaxAge(maxAgeContext: MaxAgeContext): Duration {
    if (maxAgeContext.fieldPath.size == 1) {
      // Root field
      return defaultMaxAge
    }

    val fieldName = maxAgeContext.fieldPath.last().name
    val fieldParentType = maxAgeContext.fieldPath[maxAgeContext.fieldPath.lastIndex - 1].type
    val fieldParentTypeNames = (listOf(fieldParentType) + fieldParentType.allImplements()).map { it.name }
    val fieldMaxAge = fieldParentTypeNames.firstNotNullOfOrNull { typeName ->
      val fieldCoordinates = "$typeName.$fieldName"
      maxAges[fieldCoordinates]
    }
    val computedFieldMaxAge = when (fieldMaxAge) {
      is MaxAge.Duration -> {
        fieldMaxAge.duration
      }

      is MaxAge.Inherit -> {
        getParentMaxAge(maxAgeContext)
      }

      null -> {
        getTypeMaxAge(maxAgeContext)
      }
    }
    val isRootField = maxAgeContext.fieldPath.size == 2
    return if (isRootField) {
      computedFieldMaxAge
    } else {
      minOf(computedFieldMaxAge, getParentMaxAge(maxAgeContext))
    }
  }

  private fun getParentMaxAge(maxAgeContext: MaxAgeContext): Duration = getMaxAge(MaxAgeContext(maxAgeContext.fieldPath.dropLast(1)))

  private fun getTypeMaxAge(maxAgeContext: MaxAgeContext): Duration {
    val field = maxAgeContext.fieldPath.last()
    val fieldTypeNames = (listOf(field.type) + field.type.allImplements()).map { it.name }
    val typeMaxAge = fieldTypeNames.firstNotNullOfOrNull { maxAges[it] }
    return when (typeMaxAge) {
      is MaxAge.Duration -> {
        typeMaxAge.duration
      }

      is MaxAge.Inherit -> {
        getParentMaxAge(maxAgeContext)
      }

      null -> {
        getFallbackMaxAge(maxAgeContext)
      }
    }
  }

  // Fallback:
  // - root fields have the default maxAge
  // - same for fields that return a composite type
  // - non root fields that return a leaf type inherit the maxAge of their parent field
  private fun getFallbackMaxAge(maxAgeContext: MaxAgeContext): Duration {
    val field = maxAgeContext.fieldPath.last()
    val isRootField = maxAgeContext.fieldPath.size == 2
    return if (isRootField || field.type.isComposite) {
      defaultMaxAge
    } else {
      getParentMaxAge(maxAgeContext)
    }
  }
}

/**
 * Returns all the implemented types.
 * We go breadth first so they are returned in the order they are defined in the schema.
 */
private fun MaxAgeContext.Type.allImplements(): List<MaxAgeContext.Type> {
  val allImplements = mutableListOf<MaxAgeContext.Type>()
  val queue = ArrayDeque<MaxAgeContext.Type>()
  queue.addAll(implements)
  while (queue.isNotEmpty()) {
    val current = queue.removeFirst()
    allImplements.add(current)
    queue.addAll(current.implements)
  }
  return allImplements.distinct()
}

internal fun CompiledField.toMaxAgeField(): MaxAgeContext.Field {
  val type = type.rawType()
  val implements: List<MaxAgeContext.Type> = when (type) {
    is ObjectType -> {
      type.implements.map { it.toMaxAgeType() }
    }

    is InterfaceType -> {
      type.implements.map { it.toMaxAgeType() }
    }

    else -> {
      emptyList()
    }
  }
  return MaxAgeContext.Field(
      name = name,
      type = MaxAgeContext.Type(
          name = type.name,
          isComposite = type.isComposite(),
          implements = implements,
      )
  )
}

private fun InterfaceType.toMaxAgeType(): MaxAgeContext.Type = MaxAgeContext.Type(
    name = name,
    isComposite = true,
    implements = implements.map { it.toMaxAgeType() },
)
