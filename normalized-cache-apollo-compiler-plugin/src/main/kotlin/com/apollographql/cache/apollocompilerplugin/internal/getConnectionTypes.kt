package com.apollographql.cache.apollocompilerplugin.internal

import com.apollographql.apollo.annotations.ApolloExperimental
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
