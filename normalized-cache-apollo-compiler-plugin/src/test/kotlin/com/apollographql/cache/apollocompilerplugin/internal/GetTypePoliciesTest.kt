@file:OptIn(ApolloExperimental::class)

package com.apollographql.cache.apollocompilerplugin.internal

import com.apollographql.apollo.annotations.ApolloExperimental
import com.apollographql.apollo.ast.ForeignSchema
import com.apollographql.apollo.ast.SourceAwareException
import com.apollographql.apollo.ast.internal.SchemaValidationOptions
import com.apollographql.apollo.ast.parseAsGQLDocument
import com.apollographql.apollo.ast.validateAsSchema
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GetTypePoliciesTest {
  @Test
  fun simpleTypePolicies() {
    // language=GraphQL
    val schema = """
      type Query {
        user: User
      }
      
      type User @typePolicy(keyFields: "id") {
        id: ID!
      }
      
      interface Animal @typePolicy(keyFields: "kingdom species") {
        kingdom: String!
        species: String!
      }
      
      type Lion implements Animal {
        kingdom: String!
        species: String!
      }
      
      interface HasId @typePolicy(keyFields: "id") {
        id: ID!
      }
      
      type Circle implements HasId {
        id: ID!
      }
      
      type Square @typePolicy(keyFields: "radius") {
        radius: Float!
      }
      
      union Shape = Circle | Square
    """.trimIndent()
        .parseAsGQLDocument().getOrThrow()
        .validateAsSchema(
            SchemaValidationOptions(
                addKotlinLabsDefinitions = true,
                foreignSchemas = emptyList()
            )
        ).getOrThrow()

    val expected = mapOf(
        "User" to TypePolicy(keyFields = setOf("id")),
        "Animal" to TypePolicy(keyFields = setOf("kingdom", "species")),
        "Lion" to TypePolicy(keyFields = setOf("kingdom", "species")),
        "HasId" to TypePolicy(keyFields = setOf("id")),
        "Circle" to TypePolicy(keyFields = setOf("id")),
        "Square" to TypePolicy(keyFields = setOf("radius")),
    )

    assertEquals(expected, schema.getTypePolicies())
  }

  // TODO: Ignored because the same checks are already done in validateAsSchema in v4.
  // TODO: Un-ignore when using v5.
  @Ignore
  @Test
  fun `cannot inherit different keys from different interfaces`() {
    // language=GraphQL
    val schema = """
      type Query {
        animal: Animal
      }
      
      interface Animal @typePolicy(keyFields: "kingdom species") {
        kingdom: String!
        species: String!
      }
      
      interface HasId @typePolicy(keyFields: "id") {
        id: ID!
      }
      
      type Lion implements Animal & HasId {
        kingdom: String!
        species: String!
        id: ID!
      }
    """.trimIndent()
        .parseAsGQLDocument().getOrThrow()
        .validateAsSchema(
            SchemaValidationOptions(
                addKotlinLabsDefinitions = true,
                foreignSchemas = listOf(ForeignSchema("cache", "v0.1", cacheGQLDefinitions))
            )
        ).getOrThrow()

    assertFailsWith<SourceAwareException> {
      schema.getTypePolicies()
    }.apply {
      assertEquals("""
        e: null: (14, 1): Apollo: Type 'Lion' cannot inherit different keys from different interfaces:
        Animal: [kingdom, species]
        HasId: [id]
      """.trimIndent(), message
      )
    }
  }

  // TODO: Ignored because the same checks are already done in validateAsSchema in v4.
  // TODO: Un-ignore when using v5.
  @Ignore
  @Test
  fun `type cannot have key fields since it implements interfaces which also have key fields`() {
    // language=GraphQL
    val schema = """
      type Query {
        animal: Animal
      }
      
      interface Animal @typePolicy(keyFields: "kingdom species") {
        kingdom: String!
        species: String!
      }
      
      type Lion implements Animal @typePolicy(keyFields: "id") {
        kingdom: String!
        species: String!
        id: ID!
      }
    """.trimIndent()
        .parseAsGQLDocument().getOrThrow()
        .validateAsSchema(
            SchemaValidationOptions(
                addKotlinLabsDefinitions = true,
                foreignSchemas = listOf(ForeignSchema("cache", "v0.1", cacheGQLDefinitions))
            )
        ).getOrThrow()

    assertFailsWith<SourceAwareException> {
      schema.getTypePolicies()
    }.apply {
      assertEquals("""
        e: null: (10, 1): Type 'Lion' cannot have key fields since it implements the following interfaces which also have key fields: Animal: [kingdom, species]
      """.trimIndent(), message
      )
    }
  }
}
