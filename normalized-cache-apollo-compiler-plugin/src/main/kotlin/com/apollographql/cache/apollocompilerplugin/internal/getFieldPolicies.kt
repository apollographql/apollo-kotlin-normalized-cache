package com.apollographql.cache.apollocompilerplugin.internal

import com.apollographql.apollo.ast.GQLDirective
import com.apollographql.apollo.ast.GQLObjectTypeDefinition
import com.apollographql.apollo.ast.GQLStringValue
import com.apollographql.apollo.ast.Schema
import com.apollographql.apollo.ast.Schema.Companion.FIELD_POLICY
import com.apollographql.apollo.ast.SourceAwareException
import com.apollographql.cache.apollocompilerplugin.internal.FieldPolicies.FieldPolicy

internal data class FieldPolicies(
    val fieldPolicies: Map<String, FieldPolicy>,
) {
  internal data class FieldPolicy(
      val keyArgs: List<String>,
  )
}

/**
 * Returns the field policies for object types in the schema.
 */
internal fun Schema.getFieldPolicies(): Map<String, FieldPolicies> {
  @Suppress("UNCHECKED_CAST")
  return typeDefinitions.values
      .filterIsInstance<GQLObjectTypeDefinition>()
      .associate {
        it.name to validateAndComputeFieldPolicies(it)
      }
      .filterValues { it != null }
      .mapValues { FieldPolicies(it.value!!) }
}

private fun Schema.validateAndComputeFieldPolicies(typeDefinition: GQLObjectTypeDefinition): Map<String, FieldPolicy>? {
  val fieldPolicyDirectives = typeDefinition.directives.filter { originalDirectiveName(it.name) == FIELD_POLICY }.ifEmpty { return null }
  val fieldPolicies = fieldPolicyDirectives.associate { it.toFieldPolicy() }
  for ((fieldName, keyArgs) in fieldPolicies) {
    val field = typeDefinition.fields.singleOrNull { it.name == fieldName }
    if (field == null) {
      throw SourceAwareException("Apollo: unknown field '${typeDefinition.name}.$fieldName' in @fieldPolicy", typeDefinition.sourceLocation)
    }
    for (keyArg in keyArgs.keyArgs) {
      if (field.arguments.none { it.name == keyArg }) {
        throw SourceAwareException("Apollo: unknown argument '${typeDefinition.name}.$fieldName($keyArg:)' in @fieldPolicy", typeDefinition.sourceLocation)
      }
    }
  }
  return fieldPolicies
}

private fun GQLDirective.toFieldPolicy(): Pair<String, FieldPolicy> {
  val forField = (arguments.singleOrNull { it.name == "forField" }?.value as? GQLStringValue)?.value
      ?: throw SourceAwareException("Apollo: missing or wrong type 'forField' argument in @fieldPolicy", sourceLocation)
  return forField to FieldPolicy(extractFields("keyArgs"))
}
