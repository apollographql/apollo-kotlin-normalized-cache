package com.apollographql.cache.apollocompilerplugin.internal

import com.apollographql.apollo.ast.GQLInterfaceTypeDefinition
import com.apollographql.apollo.ast.GQLObjectTypeDefinition
import com.apollographql.apollo.ast.GQLStringValue
import com.apollographql.apollo.ast.Schema
import com.apollographql.apollo.ast.Schema.Companion.TYPE_POLICY
import com.apollographql.apollo.ast.rawType
import com.apollographql.apollo.compiler.ApolloCompiler

internal data class EmbeddedFields(
    val embeddedFields: List<String>,
)

internal fun Schema.getEmbeddedFields(
    logger: ApolloCompiler.Logger,
    connectionTypes: Set<String>,
): Map<String, EmbeddedFields> {
  val issueLogger = IssueLogger(logger)
  // Fields manually specified as embedded
  val embeddedFields = mutableMapOf<String, MutableList<String>>()
  for (typeDefinition in typeDefinitions.values.filterIsInstance<GQLObjectTypeDefinition>()) {
    // Look at @embedded
    for (field in typeDefinition.fields) {
      val embeddedDirective = field.directives.firstOrNull { this.originalDirectiveName(it.name) == EMBEDDED }
      if (embeddedDirective != null) {
        embeddedFields.getOrPut(typeDefinition.name) { mutableListOf() }.add(field.name)
      }
    }

    // Look at @embeddedField(name: "fieldName")
    val embeddedFieldDirectives = typeDefinition.directives.filter { this.originalDirectiveName(it.name) == EMBEDDED_FIELD }
    for (fieldDirective in embeddedFieldDirectives) {
      val fieldName = (fieldDirective.arguments.first { it.name == "name" }.value as GQLStringValue).value
      if (typeDefinition.fields.none { it.name == fieldName }) {
        issueLogger.logIssue("Field `$fieldName` does not exist on type `${typeDefinition.name}`", fieldDirective.sourceLocation)
        continue
      }
      embeddedFields.getOrPut(typeDefinition.name) { mutableListOf() }.add(fieldName)
    }

    // Legacy: also look at @typePolicy(embedded = "field1 field2")
    val typePolicyDirective = typeDefinition.directives.firstOrNull { this.originalDirectiveName(it.name) == TYPE_POLICY } ?: continue
    val embeddedFieldNames = typePolicyDirective.extractFields("embeddedFields")
    for (fieldName in embeddedFieldNames) {
      if (typeDefinition.fields.none { it.name == fieldName }) {
        issueLogger.logIssue("Field `$fieldName` does not exist on type `${typeDefinition.name}`", typePolicyDirective.sourceLocation)
        continue
      } else {
        embeddedFields.getOrPut(typeDefinition.name) { mutableListOf() }.add(fieldName)
      }
    }
  }

  // Fields that are of a connection type
  val connectionFields: Map<String, List<String>> = getConnectionFields(connectionTypes)
  // Specific Connection type fields
  val connectionTypeFields: Map<String, List<String>> = connectionTypes.associateWith { listOf("edges", "pageInfo") }
  // Merge all
  return (embeddedFields.entries + connectionFields.entries + connectionTypeFields.entries)
      .groupBy({ it.key }, { it.value })
      .mapValues { entry -> EmbeddedFields(entry.value.flatten().distinct()) }
}

private const val EMBEDDED = "embedded"
private const val EMBEDDED_FIELD = "embeddedField"

private fun Schema.getConnectionFields(connectionTypes: Set<String>): Map<String, List<String>> {
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
        }
        if (connectionFields.isNotEmpty()) {
          typeDefinition.name to connectionFields
        } else {
          null
        }
      }
      .toMap()
}
