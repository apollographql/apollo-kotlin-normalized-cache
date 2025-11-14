package com.apollographql.cache.apollocompilerplugin.internal

import com.apollographql.apollo.annotations.ApolloExperimental
import com.apollographql.apollo.ast.GQLInterfaceTypeDefinition
import com.apollographql.apollo.ast.GQLObjectTypeDefinition
import com.apollographql.apollo.ast.GQLUnionTypeDefinition
import com.apollographql.apollo.ast.Schema
import com.apollographql.apollo.ast.Schema.Companion.TYPE_POLICY
import com.apollographql.apollo.ast.rawType

@OptIn(ApolloExperimental::class)
internal fun Schema.getConnectionTypes(): Set<String> {
  // Look at @connection
  return typeDefinitions.filter { it.value.directives.any { directive -> originalDirectiveName(directive.name) == CONNECTION } }.keys +
      // Legacy: also look at @typePolicy(connectionFields = "field1 field2")
      typeDefinitions.values.flatMap { typeDefinition ->
        typeDefinition.directives.firstOrNull { originalDirectiveName(it.name) == TYPE_POLICY }?.extractFields("connectionFields").orEmpty()
            .mapNotNull { field ->
              typeDefinition.fields.firstOrNull { it.name == field }?.type?.rawType()?.name
            }
      }
}

private const val CONNECTION = "connection"


/**
 * Validates that connection types have an `edges` field whose type has a `node` field whose possible types have key fields defined.
 */
internal fun Schema.validateConnectionTypes(connectionTypes: Set<String>, typePolicies: Map<String, TypePolicy>): List<String> {
  val errors = mutableListOf<String>()
  for (connectionTypeName in connectionTypes) {
    val connectionTypeDefinition = typeDefinitions[connectionTypeName]!!
    val edgesField = connectionTypeDefinition.fields.firstOrNull { it.name == "edges" }
    if (edgesField == null) {
      errors.add("Connection type '$connectionTypeName' must have an 'edges' field")
      continue
    }
    val edgesType = edgesField.type.rawType()
    val edgesTypeDefinition = typeDefinitions[edgesType.name]!!
    val nodeField = edgesTypeDefinition.fields.firstOrNull { it.name == "node" }
    if (nodeField == null) {
      errors.add("The type of '$connectionTypeName.edges', '${edgesType.name}', must have a 'node' field")
      continue
    }
    val nodeType = nodeField.type.rawType()
    val nodeTypeDefinition = typeDefinitions[nodeType.name]!!
    when (nodeTypeDefinition) {
      is GQLInterfaceTypeDefinition, is GQLObjectTypeDefinition -> {
        val nodeTypePolicy = typePolicies[nodeType.name]
        if (nodeTypePolicy == null || nodeTypePolicy.keyFields.isEmpty()) {
          errors.add("The type of '${edgesType.name}.node', '${nodeType.name}', must have key fields defined with @typePolicy")
        }
      }

      is GQLUnionTypeDefinition -> {
        val nodePossibleTypeNames = possibleTypes(nodeTypeDefinition)
        for (nodePossibleTypeName in nodePossibleTypeNames) {
          val nodePossibleTypePolicy = typePolicies[nodePossibleTypeName]
          if (nodePossibleTypePolicy == null || nodePossibleTypePolicy.keyFields.isEmpty()) {
            errors.add("The type '${nodePossibleTypeName}' which is a possible type of '${edgesType.name}.node' must have key fields defined with @typePolicy")
          }
        }
      }

      else -> {}
    }
  }
  return errors
}
