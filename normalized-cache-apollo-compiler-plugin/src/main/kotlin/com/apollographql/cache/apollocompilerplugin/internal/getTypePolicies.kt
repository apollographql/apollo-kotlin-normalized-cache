package com.apollographql.cache.apollocompilerplugin.internal

import com.apollographql.apollo.ast.GQLDirective
import com.apollographql.apollo.ast.GQLField
import com.apollographql.apollo.ast.GQLInterfaceTypeDefinition
import com.apollographql.apollo.ast.GQLObjectTypeDefinition
import com.apollographql.apollo.ast.GQLStringValue
import com.apollographql.apollo.ast.GQLTypeDefinition
import com.apollographql.apollo.ast.Schema
import com.apollographql.apollo.ast.Schema.Companion.TYPE_POLICY
import com.apollographql.apollo.ast.SourceAwareException
import com.apollographql.apollo.ast.parseAsGQLSelections

internal data class TypePolicy(
    val keyFields: Set<String>,
)

/**
 * Returns the type policies for object types and interfaces in the schema.
 */
internal fun Schema.getTypePolicies(): Map<String, TypePolicy> {
  val typePolicyCache = mutableMapOf<String, TypePolicy?>()
  @Suppress("UNCHECKED_CAST")
  return typeDefinitions.values
      .filter { it is GQLObjectTypeDefinition || it is GQLInterfaceTypeDefinition }
      .associate {
        it.name to validateAndComputeTypePolicy(it, typePolicyCache)
      }
      .filterValues { it != null } as Map<String, TypePolicy>
}

/**
 * Returns the type policy for this type definition.
 *
 * If an interface defines a type policy, its subtypes inherit that type policy. It is an error trying to redefine the type policy in a subtype.
 */
private fun Schema.validateAndComputeTypePolicy(
    typeDefinition: GQLTypeDefinition,
    typePolicyCache: MutableMap<String, TypePolicy?>,
): TypePolicy? {
  if (typePolicyCache.contains(typeDefinition.name)) {
    return typePolicyCache[typeDefinition.name]
  }
  val (directives, interfaces) = when (typeDefinition) {
    is GQLObjectTypeDefinition -> typeDefinition.directives to typeDefinition.implementsInterfaces
    is GQLInterfaceTypeDefinition -> typeDefinition.directives to typeDefinition.implementsInterfaces
    else -> error("Unexpected $typeDefinition")
  }

  val interfacesTypePolicy = interfaces.mapNotNull { validateAndComputeTypePolicy(typeDefinitions[it]!!, typePolicyCache) }
  val distinct = interfacesTypePolicy.distinct()
  if (distinct.size > 1) {
    val extra = interfaces.indices.joinToString("\n") {
      "${interfaces[it]}: ${interfacesTypePolicy[it]}"
    }
    throw SourceAwareException(
        error = "Apollo: Type '${typeDefinition.name}' cannot inherit different @typePolicy from different interfaces:\n$extra",
        sourceLocation = typeDefinition.sourceLocation
    )
  }

  val typePolicyDirective = directives.firstOrNull { originalDirectiveName(it.name) == TYPE_POLICY }
  val typePolicy = typePolicyDirective?.toTypePolicy()
  val ret = if (typePolicy != null) {
    if (distinct.isNotEmpty()) {
      val extra = interfaces.indices.joinToString("\n") {
        "${interfaces[it]}: ${interfacesTypePolicy[it]}"
      }
      throw SourceAwareException(
          error = "Apollo: Type '${typeDefinition.name}' cannot have @typePolicy since it implements the following interfaces which also have @typePolicy: $extra",
          sourceLocation = typeDefinition.sourceLocation
      )
    }
    typePolicy
  } else {
    distinct.firstOrNull()
  }
  typePolicyCache[typeDefinition.name] = ret
  return ret
}

private fun GQLDirective.toTypePolicy(): TypePolicy {
  val keyFields = (((arguments.singleOrNull { it.name == "keyFields" }?.value as? GQLStringValue)?.value ?: "")
      .parseAsGQLSelections().value?.map { gqlSelection ->
        (gqlSelection as GQLField).name
      } ?: throw SourceAwareException("Apollo: keyArgs should be a selectionSet", sourceLocation))
      .toSet()

  return TypePolicy(
      keyFields = keyFields,
  )
}
