package com.apollographql.cache.normalized.api

import com.apollographql.apollo.api.CompiledField
import com.apollographql.apollo.api.CompiledNamedType

/**
 * A provider for fields whose value should be embedded in their [Record], rather than being dereferenced during normalization.
 *
 * An [EmbeddedFieldsProvider] can be used in conjunction with [RecordMerger] and [MetadataGenerator] to access multiple fields and their metadata in a single
 * [Record].
 */
interface EmbeddedFieldsProvider {
  /**
   * Returns whether the field should be embedded.
   */
  fun isEmbedded(context: EmbeddedFieldsContext): Boolean
}

/**
 * A context passed to [EmbeddedFieldsProvider.isEmbedded].
 * @see [EmbeddedFieldsProvider.isEmbedded]
 */
class EmbeddedFieldsContext(
    val obj: DataWithErrors,
    val parentType: CompiledNamedType,
    val field: CompiledField,
)

object EmptyEmbeddedFieldsProvider : EmbeddedFieldsProvider {
  override fun isEmbedded(context: EmbeddedFieldsContext) = false
}

/**
 * An [EmbeddedFieldsProvider] that returns the fields specified by the `@typePolicy(embeddedFields: "...")` and `@connection` directives.
 */
class DefaultEmbeddedFieldsProvider(
    private val embeddedFields: Map<String, EmbeddedFields>,
) : EmbeddedFieldsProvider {
  override fun isEmbedded(context: EmbeddedFieldsContext): Boolean {
    // Try the server returned type first
    val typeName = context.obj["__typename"]?.toString()
    val embeddedFields = typeName?.let { embeddedFields[it] } ?:
    // Fallback to the schema type
    embeddedFields[context.parentType.rawType().name] ?: return false
    return context.field.name in embeddedFields.embeddedFields
  }
}

/**
 * A [Relay connection types](https://relay.dev/graphql/connections.htm#sec-Connection-Types) aware [EmbeddedFieldsProvider].
 */
class ConnectionEmbeddedFieldsProvider(
    /**
     * The connection type names.
     */
    private val connectionTypes: Set<String>,
) : EmbeddedFieldsProvider {
  companion object {
    private val connectionFieldsToEmbed = setOf("pageInfo", "edges")
  }

  override fun isEmbedded(context: EmbeddedFieldsContext): Boolean {
    return context.field.type.rawType().name in connectionTypes ||
        context.parentType.rawType().name in connectionTypes && context.field.name in connectionFieldsToEmbed
  }
}
