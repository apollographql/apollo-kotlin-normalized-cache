package com.apollographql.cache.apollocompilerplugin.internal

import com.apollographql.apollo.ast.GQLDirective
import com.apollographql.apollo.ast.GQLField
import com.apollographql.apollo.ast.GQLInterfaceTypeDefinition
import com.apollographql.apollo.ast.GQLObjectTypeDefinition
import com.apollographql.apollo.ast.GQLStringValue
import com.apollographql.apollo.ast.GQLTypeDefinition
import com.apollographql.apollo.ast.SourceAwareException
import com.apollographql.apollo.ast.parseAsGQLSelections

internal val GQLTypeDefinition.fields
  get() = when (this) {
    is GQLObjectTypeDefinition -> fields
    is GQLInterfaceTypeDefinition -> fields
    else -> emptyList()
  }

internal fun GQLDirective.extractFields(argumentName: String): Set<String> {
  return (((arguments.singleOrNull { it.name == argumentName }?.value as? GQLStringValue)?.value ?: "")
      .parseAsGQLSelections().value?.map { gqlSelection ->
        if (gqlSelection !is GQLField) {
          throw SourceAwareException("Apollo: $argumentName values should be field selections", sourceLocation)
        }
        gqlSelection.name
      } ?: throw SourceAwareException("Apollo: $argumentName should be a selectionSet", sourceLocation))
      .toSet()
}
