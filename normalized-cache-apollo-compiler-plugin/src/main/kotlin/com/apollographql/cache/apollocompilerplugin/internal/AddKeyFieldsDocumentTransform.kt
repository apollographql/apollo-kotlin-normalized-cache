@file:OptIn(ApolloExperimental::class)

package com.apollographql.cache.apollocompilerplugin.internal

import com.apollographql.apollo.annotations.ApolloExperimental
import com.apollographql.apollo.annotations.ApolloInternal
import com.apollographql.apollo.ast.GQLDocument
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
import com.apollographql.apollo.ast.SourceAwareException
import com.apollographql.apollo.ast.definitionFromScope
import com.apollographql.apollo.ast.rawType
import com.apollographql.apollo.ast.responseName
import com.apollographql.apollo.ast.rootTypeDefinition
import com.apollographql.apollo.compiler.ExecutableDocumentTransform

/**
 * Add `__typename` to every composite selection set and key fields to selection sets on types where `@typePolicy` declare them.
 */
internal object AddKeyFieldsExecutableDocumentTransform : ExecutableDocumentTransform {
  override fun transform(
      schema: Schema,
      document: GQLDocument,
      extraFragmentDefinitions: List<GQLFragmentDefinition>,
  ): GQLDocument {
    val keyFields = schema.getTypePolicies().mapValues { it.value.keyFields }
    return document.copy(
        definitions = document.definitions.map {
          when (it) {
            is GQLFragmentDefinition -> it.withRequiredFields(schema, keyFields)
            is GQLOperationDefinition -> it.withRequiredFields(schema, keyFields)
            else -> it
          }
        }
    )
  }

  private fun GQLOperationDefinition.withRequiredFields(schema: Schema, keyFields: Map<String, Set<String>>): GQLOperationDefinition {
    val parentType = rootTypeDefinition(schema)!!.name
    return copy(
        selections = selections.withRequiredFields(
            schema = schema,
            keyFields = keyFields,
            parentType = parentType,
            isRoot = false,
        )
    )
  }

  private fun GQLFragmentDefinition.withRequiredFields(schema: Schema, keyFields: Map<String, Set<String>>): GQLFragmentDefinition {
    return copy(
        selections = selections.withRequiredFields(
            schema = schema,
            keyFields = keyFields,
            parentType = typeCondition.name,
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
      keyFields: Map<String, Set<String>>,
      parentType: String,
      isRoot: Boolean,
  ): List<GQLSelection> {
    if (isEmpty()) {
      return this
    }
    val newSelections = this.map {
      when (it) {
        is GQLInlineFragment -> {
          it.copy(
              selections = it.selections.withRequiredFields(
                  schema = schema,
                  keyFields = keyFields,
                  parentType = it.typeCondition?.name ?: parentType,
                  isRoot = false,
              )
          )
        }

        is GQLFragmentSpread -> it
        is GQLField -> it.withRequiredFields(
            schema = schema,
            keyFields = keyFields,
            parentType = parentType
        )
      }
    }

    if (!isRoot) {
      return newSelections
    }

    val parentTypeKeyFields = keyFields[parentType] ?: emptySet()
    newSelections.filterIsInstance<GQLField>().forEach {
      // Disallow fields whose alias conflicts with a key field, or is "__typename"
      if (parentTypeKeyFields.contains(it.alias) || it.alias == "__typename") {
        throw SourceAwareException(
            error = "Apollo: Field '${it.alias}: ${it.name}' in $parentType conflicts with key fields",
            sourceLocation = it.sourceLocation
        )
      }
    }

    // Add key fields
    val fieldNames = newSelections.filterIsInstance<GQLField>().map { it.responseName() }
    val fieldNamesToAdd = (parentTypeKeyFields - fieldNames)

    // Unions and interfaces without key fields: add key fields of all possible types in inline fragments
    val inlineFragmentsToAdd = if (parentTypeKeyFields.isEmpty()) {
      val parentTypeDefinition = schema.typeDefinition(parentType)
      val possibleTypes = if (parentTypeDefinition is GQLInterfaceTypeDefinition || parentTypeDefinition is GQLUnionTypeDefinition) {
        schema.possibleTypes(parentTypeDefinition)
      } else {
        emptySet()
      }
      possibleTypes
          .associateWith { possibleType -> keyFields[possibleType] ?: emptySet() }
          .mapNotNull { (possibleType, possibleTypeKeyFields) ->
            val fieldNamesToAddInInlineFragment = possibleTypeKeyFields - fieldNames
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

    val selectionsWithAdditions = newSelections + fieldNamesToAdd.map { buildField(it) } + inlineFragmentsToAdd
    // Remove the __typename if it exists and add it again at the top, so we're guaranteed to have it at the beginning of json parsing.
    // Also remove any @include/@skip directive on __typename.
    return listOf(buildField("__typename")) + selectionsWithAdditions.filter { (it as? GQLField)?.name != "__typename" }
  }

  private fun GQLField.withRequiredFields(
      schema: Schema,
      keyFields: Map<String, Set<String>>,
      parentType: String,
  ): GQLField {
    val typeDefinition = definitionFromScope(schema, parentType)!!
    val newSelectionSet = selections.withRequiredFields(
        schema = schema,
        keyFields = keyFields,
        parentType = typeDefinition.type.rawType().name,
        isRoot = true,
    )
    return copy(selections = newSelectionSet)
  }

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
