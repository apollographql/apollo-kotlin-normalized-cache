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

internal fun Schema.validateAndComputeKeyFields(): Map<String, Set<String>> {
  val keyFieldsCache = mutableMapOf<String, Set<String>>()
  typeDefinitions.values.filter { it.canHaveKeyFields() }.forEach {
    keyFields(it, keyFieldsCache)
  }
  return keyFieldsCache.filterValues { it.isNotEmpty() }
}

private fun Schema.keyFields(
    typeDefinition: GQLTypeDefinition,
    keyFieldsCache: MutableMap<String, Set<String>>,
): Set<String> {
  val cached = keyFieldsCache[typeDefinition.name]
  if (cached != null) {
    return cached
  }

  val (directives, interfaces) = when (typeDefinition) {
    is GQLObjectTypeDefinition -> typeDefinition.directives to typeDefinition.implementsInterfaces
    is GQLInterfaceTypeDefinition -> typeDefinition.directives to typeDefinition.implementsInterfaces
    else -> error("Cannot get directives for $typeDefinition")
  }

  val interfacesKeyFields = interfaces.map { keyFields(typeDefinitions[it]!!, keyFieldsCache) }
      .filter { it.isNotEmpty() }
  val distinct = interfacesKeyFields.distinct()
  if (distinct.size > 1) {
    val extra = interfaces.indices.joinToString("\n") {
      "${interfaces[it]}: ${interfacesKeyFields[it]}"
    }
    throw SourceAwareException(
        error = "Apollo: Type '${typeDefinition.name}' cannot inherit different keys from different interfaces:\n$extra",
        sourceLocation = typeDefinition.sourceLocation
    )
  }

  val keyFields = directives.filter { originalDirectiveName(it.name) == TYPE_POLICY }.toKeyFields()
  val ret = if (keyFields.isNotEmpty()) {
    if (distinct.isNotEmpty()) {
      val extra = interfaces.indices.joinToString("\n") {
        "${interfaces[it]}: ${interfacesKeyFields[it]}"
      }
      throw SourceAwareException(
          error = "Type '${typeDefinition.name}' cannot have key fields since it implements the following interfaces which also have key fields: $extra",
          sourceLocation = typeDefinition.sourceLocation
      )
    }
    keyFields
  } else {
    distinct.firstOrNull() ?: emptySet()
  }

  keyFieldsCache[typeDefinition.name] = ret

  return ret
}

private fun GQLTypeDefinition.canHaveKeyFields(): Boolean {
  return this is GQLObjectTypeDefinition || this is GQLInterfaceTypeDefinition
}


private fun List<GQLDirective>.toKeyFields(): Set<String> = extractFields("keyFields")

private fun List<GQLDirective>.extractFields(argumentName: String): Set<String> {
  if (isEmpty()) {
    return emptySet()
  }
  return flatMap {
    val value = it.arguments.firstOrNull {
      it.name == argumentName
    }?.value

    val selectionSet = (value as? GQLStringValue)?.value ?: return@flatMap emptyList()

    selectionSet.parseAsGQLSelections().getOrThrow().map { gqlSelection ->
      // No need to check here, this should be done during validation
      (gqlSelection as GQLField).name
    }
  }.toSet()
}
