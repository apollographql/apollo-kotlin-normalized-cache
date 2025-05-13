@file:OptIn(ApolloExperimental::class)

package com.apollographql.cache.apollocompilerplugin.internal

import com.apollographql.apollo.annotations.ApolloExperimental
import com.apollographql.apollo.annotations.ApolloInternal
import com.apollographql.apollo.ast.GQLField
import com.apollographql.apollo.ast.GQLFragmentDefinition
import com.apollographql.apollo.ast.GQLFragmentSpread
import com.apollographql.apollo.ast.GQLInlineFragment
import com.apollographql.apollo.ast.GQLInterfaceTypeDefinition
import com.apollographql.apollo.ast.GQLNamedType
import com.apollographql.apollo.ast.GQLOperationDefinition
import com.apollographql.apollo.ast.GQLSelection
import com.apollographql.apollo.ast.GQLUnionTypeDefinition
import com.apollographql.apollo.ast.Schema
import com.apollographql.apollo.ast.definitionFromScope
import com.apollographql.apollo.ast.rawType
import com.apollographql.apollo.ast.responseName
import com.apollographql.apollo.ast.rootTypeDefinition
import com.apollographql.apollo.compiler.DocumentTransform

/**
 * Add key fields and `__typename` to selections on types that declare them via `@typePolicy`.
 */
internal class AddKeyFieldsDocumentTransform : DocumentTransform {
  override fun transform(schema: Schema, fragment: GQLFragmentDefinition): GQLFragmentDefinition {
    return fragment.withRequiredFields(schema)
  }

  override fun transform(schema: Schema, operation: GQLOperationDefinition): GQLOperationDefinition {
    return operation.withRequiredFields(schema)
  }

  private fun GQLOperationDefinition.withRequiredFields(schema: Schema): GQLOperationDefinition {
    val parentType = rootTypeDefinition(schema)!!.name
    return copy(
        selections = selections.withRequiredFields(
            schema = schema,
            parentType = parentType,
            parentFields = emptySet(),
            isRoot = false,
        )
    )
  }

  private fun GQLFragmentDefinition.withRequiredFields(schema: Schema): GQLFragmentDefinition {
    return copy(
        selections = selections.withRequiredFields(
            schema = schema,
            parentType = typeCondition.name,
            parentFields = emptySet(),
            isRoot = true,
        ),
    )
  }

  /**
   * @param isRoot: whether this selection set is considered a valid root for adding __typename
   * This is the case for field selection sets but also fragments since fragments can be executed from the cache
   */
  @OptIn(ApolloInternal::class)
  private fun List<GQLSelection>.withRequiredFields(
      schema: Schema,
      parentType: String,
      parentFields: Set<String>,
      isRoot: Boolean,
  ): List<GQLSelection> {
    if (isEmpty()) {
      return this
    }
    val keyFields = schema.keyFields(parentType)
    val fieldNames = parentFields + this.filterIsInstance<GQLField>().map { it.responseName() }

    val alreadyHandledTypes = mutableSetOf<String>()
    var newSelections = this.map {
      when (it) {
        is GQLInlineFragment -> {
          alreadyHandledTypes += it.typeCondition?.name ?: parentType
          it.copy(
              selections = it.selections.withRequiredFields(
                  schema = schema,
                  parentType = it.typeCondition?.name ?: parentType,
                  parentFields = fieldNames + keyFields,
                  isRoot = false
              )
          )
        }

        is GQLFragmentSpread -> it
        is GQLField -> it.withRequiredFields(
            schema = schema,
            parentType = parentType
        )
      }
    }

    // Interfaces and unions: add key fields of all possible types in inline fragments
    val inlineFragmentsToAdd = if (isRoot) {
      val parentTypeDefinition = schema.typeDefinition(parentType)
      val possibleTypes = if (parentTypeDefinition is GQLInterfaceTypeDefinition || parentTypeDefinition is GQLUnionTypeDefinition) {
        schema.possibleTypes(parentTypeDefinition)
      } else {
        emptySet()
      } - alreadyHandledTypes
      possibleTypes.associateWith { schema.keyFields(it) }.mapNotNull { (possibleType, keyFields) ->
        val fieldNamesToAddInInlineFragment = keyFields - fieldNames
        if (fieldNamesToAddInInlineFragment.isNotEmpty()) {
          GQLInlineFragment(
              typeCondition = GQLNamedType(null, possibleType),
              selections = fieldNamesToAddInInlineFragment.map { buildField(it) },
              directives = emptyList(),
              sourceLocation = null,
          )
        } else {
          null
        }
      }
    } else {
      emptySet()
    }


    val fieldNamesToAdd = (keyFields - fieldNames)

    newSelections.filterIsInstance<GQLField>().forEach {
      // Verify that the fields we add won't overwrite an existing alias.
      // This is not 100% correct as this validation should be made more globally.
      check(!fieldNamesToAdd.contains(it.alias)) {
        "Field ${it.alias}: ${it.name} in $parentType conflicts with key fields"
      }
    }

    newSelections = newSelections + fieldNamesToAdd.map { buildField(it) } + inlineFragmentsToAdd

    newSelections = if (isRoot) {
      // Remove the __typename if it exists and add it again at the top, so we're guaranteed to have it at the beginning of json parsing.
      // Also remove any @include/@skip directive on __typename.
      listOf(buildField("__typename")) + newSelections.filter { (it as? GQLField)?.name != "__typename" }
    } else {
      newSelections
    }

    return newSelections
  }

  private fun GQLField.withRequiredFields(
      schema: Schema,
      parentType: String,
  ): GQLField {
    val typeDefinition = definitionFromScope(schema, parentType)!!
    val newSelectionSet = selections.withRequiredFields(
        schema = schema,
        parentType = typeDefinition.type.rawType().name,
        parentFields = emptySet(),
        isRoot = true
    )
    return copy(selections = newSelectionSet)
  }

  @OptIn(ApolloExperimental::class)
  private fun buildField(name: String): GQLField {
    return GQLField(
        name = name,
        arguments = emptyList(),
        selections = emptyList(),
        sourceLocation = null,
        directives = emptyList(),
        alias = null,
    )
  }
}
