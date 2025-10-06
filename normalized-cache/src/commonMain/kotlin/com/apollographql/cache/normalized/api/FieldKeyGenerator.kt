package com.apollographql.cache.normalized.api

import com.apollographql.apollo.api.CompiledField
import com.apollographql.apollo.api.Executable

/**
 * A generator for field keys.
 *
 * Field keys uniquely identify fields within their parent [Record]. By default they take the form of the field's name and its encoded
 * arguments, for example `hero({"episode": "Jedi"})` (see [CompiledField.nameWithArguments]).
 *
 * A [FieldKeyGenerator] can be used to customize this format, for instance to exclude certain pagination arguments when storing a
 * connection field.
 */
interface FieldKeyGenerator {
  /**
   * Returns the field key to use within its parent [Record].
   */
  fun getFieldKey(context: FieldKeyContext): String
}

/**
 * Context passed to the [FieldKeyGenerator.getFieldKey] method.
 */
class FieldKeyContext(
    val parentType: String,
    val field: CompiledField,
    val variables: Executable.Variables,
)

/**
 * A [FieldKeyGenerator] that returns the field name with its arguments, excluding pagination arguments defined with the
 * `@fieldPolicy(forField: "...", paginationArgs: "...")` directive.
 *
 * @see CompiledField.nameWithArguments
 */
object DefaultFieldKeyGenerator : FieldKeyGenerator {
  override fun getFieldKey(context: FieldKeyContext): String {
    return context.field.nameWithArguments(context.variables)
  }
}

/**
 * A [FieldKeyGenerator] that generates field keys by excluding
 * [Relay connection types](https://relay.dev/graphql/connections.htm#sec-Connection-Types) pagination arguments.
 */
class ConnectionFieldKeyGenerator(private val connectionTypes: Set<String>) : FieldKeyGenerator {
  companion object {
    private val paginationArguments = setOf("first", "last", "before", "after")
  }

  override fun getFieldKey(context: FieldKeyContext): String {
    return if (context.field.type.rawType().name in connectionTypes) {
      context.field.newBuilder()
          .arguments(
              context.field.arguments.filter { argument ->
                argument.definition.name !in paginationArguments
              }
          )
          .build()
          .nameWithArguments(context.variables)
    } else {
      DefaultFieldKeyGenerator.getFieldKey(context)
    }
  }
}
