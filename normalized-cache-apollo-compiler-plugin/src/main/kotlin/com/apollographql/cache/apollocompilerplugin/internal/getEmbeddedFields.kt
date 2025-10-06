package com.apollographql.cache.apollocompilerplugin.internal

import com.apollographql.apollo.ast.GQLInterfaceTypeDefinition
import com.apollographql.apollo.ast.GQLObjectTypeDefinition
import com.apollographql.apollo.ast.Schema
import com.apollographql.apollo.ast.rawType

internal data class EmbeddedFields(
    val embeddedFields: Set<String>,
)

internal fun Schema.getEmbeddedFields(
    typePolicies: Map<String, TypePolicy>,
    connectionTypes: Set<String>,
): Map<String, EmbeddedFields> {
  // Fields manually specified as embedded
  val embeddedFields: Map<String, Set<String>> = typePolicies
      .filter { it.value.embeddedFields.isNotEmpty() }
      .mapValues { it.value.embeddedFields }
  // Fields that are of a connection type
  val connectionFields: Map<String, Set<String>> = getConnectionFields(connectionTypes)
  // Specific Connection type fields
  val connectionTypeFields: Map<String, Set<String>> = connectionTypes.associateWith { setOf("edges", "pageInfo") }
  // Merge all
  return (embeddedFields.entries + connectionFields.entries + connectionTypeFields.entries)
      .groupBy({ it.key }, { it.value })
      .mapValues { entry -> EmbeddedFields(entry.value.flatten().toSet()) }
}

private fun Schema.getConnectionFields(connectionTypes: Set<String>): Map<String, Set<String>> {
  return typeDefinitions.values
      .filter { it is GQLObjectTypeDefinition || it is GQLInterfaceTypeDefinition }
      .mapNotNull { typeDefinition ->
        val connectionFields = typeDefinition.fields.mapNotNull { field ->
          val fieldType = field.type.rawType().name
          if (connectionTypes.contains(fieldType)) {
            field.name
          } else {
            null
          }
        }.toSet()
        if (connectionFields.isNotEmpty()) {
          typeDefinition.name to connectionFields
        } else {
          null
        }
      }
      .toMap()
}
