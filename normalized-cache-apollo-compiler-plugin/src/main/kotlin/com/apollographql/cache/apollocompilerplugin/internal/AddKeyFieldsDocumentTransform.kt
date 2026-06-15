@file:OptIn(ApolloExperimental::class)

package com.apollographql.cache.apollocompilerplugin.internal

import com.apollographql.apollo.annotations.ApolloExperimental
import com.apollographql.apollo.annotations.ApolloInternal
import com.apollographql.apollo.ast.GQLBooleanValue
import com.apollographql.apollo.ast.GQLDocument
import com.apollographql.apollo.ast.GQLField
import com.apollographql.apollo.ast.GQLFragmentDefinition
import com.apollographql.apollo.ast.GQLFragmentSpread
import com.apollographql.apollo.ast.GQLHasDirectives
import com.apollographql.apollo.ast.GQLInlineFragment
import com.apollographql.apollo.ast.GQLInterfaceTypeDefinition
import com.apollographql.apollo.ast.GQLNamedType
import com.apollographql.apollo.ast.GQLObjectTypeDefinition
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
    return AddKeyFieldsExecutableDocumentTransformProcessor(
        schema = schema,
        document = document,
        extraFragmentDefinitions = extraFragmentDefinitions,
    ).transform()
  }
}

private class AddKeyFieldsExecutableDocumentTransformProcessor(
    private val schema: Schema,
    private val document: GQLDocument,
    private val extraFragmentDefinitions: List<GQLFragmentDefinition>,
) {
  private val keyFields = schema.getTypePolicies().mapValues { it.value.keyFields }

  private val fieldsWithTypeConditionsFragmentCache: MutableMap<String, Set<FieldWithTypeCondition>> = mutableMapOf()

  fun transform(): GQLDocument {
    return document.copy(
        definitions = document.definitions.map {
          when (it) {
            is GQLFragmentDefinition -> it.withRequiredFields()
            is GQLOperationDefinition -> it.withRequiredFields()
            else -> it
          }
        }
    )
  }

  private data class FieldWithTypeCondition(
      val fieldName: String,
      val typeCondition: String,
  )

  /**
   * Get field names in the selections, associated with their type condition.
   * For instance if [typeCondition] is `ParentType`, given:
   * ```graphql
   * a
   * b @skip(if: true)
   * ... on SomeType {
   *   x
   *   z
   * }
   * ```
   * returns:
   * - a: ParentType
   * - x: SomeType
   * - z: SomeType
   */
  private fun List<GQLSelection>.fieldsWithTypeConditions(typeCondition: String): Set<FieldWithTypeCondition> = flatMap { selection ->
    when (selection) {
      is GQLInlineFragment -> if (selection.shouldIgnore()) {
        emptyList()
      } else {
        selection.selections.fieldsWithTypeConditions(selection.typeCondition?.name ?: typeCondition)
      }

      is GQLFragmentSpread -> if (selection.shouldIgnore()) {
        emptyList()
      } else {
        selection.fieldsWithTypeConditions()
      }

      is GQLField -> if (selection.shouldIgnore()) {
        emptyList()
      } else {
        listOf(
            FieldWithTypeCondition(
                fieldName = selection.name,
                typeCondition = typeCondition
            )
        )
      }
    }
  }.toSet()

  private fun GQLFragmentSpread.fieldsWithTypeConditions(): Set<FieldWithTypeCondition> {
    return fieldsWithTypeConditionsFragmentCache.getOrPut(name) {
      val fragmentDefinition = (document.definitions.filterIsInstance<GQLFragmentDefinition>() + extraFragmentDefinitions)
          .firstOrNull { it.name == name }
          ?: return@getOrPut emptySet()
      fragmentDefinition.selections.fieldsWithTypeConditions(fragmentDefinition.typeCondition.name)
    }
  }

  /**
   * Ignore when:
   * - @skip(if: true)
   * - @skip(if: $someVariable) (because we can't know)
   * - @include(if: false)
   * - @include(if: $someVariable) (because we can't know)
   */
  private fun GQLHasDirectives.shouldIgnore(): Boolean {
    return directives.any { directive ->
      directive.name == "skip" && run {
        val value = directive.arguments.firstOrNull { it.name == "if" }?.value
        value !is GQLBooleanValue || value.value
      } || directive.name == "include" && run {
        val value = directive.arguments.firstOrNull { it.name == "if" }?.value
        value !is GQLBooleanValue || !value.value
      }
    }
  }

  private fun GQLOperationDefinition.withRequiredFields(): GQLOperationDefinition {
    val parentType = rootTypeDefinition(schema)!!.name
    return copy(
        selections = selections.withRequiredFields(
            parentType = parentType,
            isRoot = false,
        )
    )
  }

  private fun GQLFragmentDefinition.withRequiredFields(): GQLFragmentDefinition {
    return copy(
        selections = selections.withRequiredFields(
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
                  parentType = it.typeCondition?.name ?: parentType,
                  isRoot = false,
              )
          )
        }

        is GQLFragmentSpread -> it
        is GQLField -> it.withRequiredFields(parentType = parentType)
      }
    }

    if (!isRoot) {
      return newSelections
    }

    val parentTypeKeyFields = keyFields[parentType] ?: emptyList()
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
    val fieldNames = newSelections.filterIsInstance<GQLField>().map { it.responseName() }.toSet()
    val fieldNamesToAdd = (parentTypeKeyFields - fieldNames)

    // Unions and interfaces without key fields: add key fields of all possible types in inline fragments
    val inlineFragmentsToAdd = if (parentTypeKeyFields.isEmpty()) {
      val parentTypeDefinition = schema.typeDefinition(parentType)
      val possibleTypes = if (parentTypeDefinition is GQLInterfaceTypeDefinition || parentTypeDefinition is GQLUnionTypeDefinition) {
        schema.possibleTypes(parentTypeDefinition)
      } else {
        emptySet()
      }
      var fieldsWithTypeConditions: Set<FieldWithTypeCondition>? = null
      possibleTypes
          .associateWith { possibleType -> keyFields[possibleType] ?: emptyList() }
          .mapNotNull { (possibleType, possibleTypeKeyFields) ->
            if (possibleTypeKeyFields.isEmpty()) {
              return@mapNotNull null
            }
            if (fieldsWithTypeConditions == null) {
              fieldsWithTypeConditions = fieldsWithTypeConditions(parentType)
            }
            val possibleTypeWithParents =
              (schema.typeDefinition(possibleType) as GQLObjectTypeDefinition).implementsInterfaces + possibleType
            val alreadySelectedFieldNames = fieldsWithTypeConditions
                .filter { it.typeCondition in possibleTypeWithParents }
                .map { it.fieldName }
                .toSet()
            val fieldNamesToAddInInlineFragment = possibleTypeKeyFields - fieldNames - alreadySelectedFieldNames
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

  private fun GQLField.withRequiredFields(parentType: String): GQLField {
    val typeDefinition = definitionFromScope(schema, parentType) ?: return this
    val newSelectionSet = selections.withRequiredFields(
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
