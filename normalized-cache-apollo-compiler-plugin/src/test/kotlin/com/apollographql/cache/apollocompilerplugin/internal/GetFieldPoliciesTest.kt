@file:OptIn(ApolloExperimental::class)
@file:Suppress("ApolloMissingGraphQLDefinitionImport", "GraphQLUnresolvedReference")

package com.apollographql.cache.apollocompilerplugin.internal

import com.apollographql.apollo.annotations.ApolloExperimental
import com.apollographql.apollo.ast.builtinForeignSchemas
import com.apollographql.apollo.ast.internal.SchemaValidationOptions
import com.apollographql.apollo.ast.parseAsGQLDocument
import com.apollographql.apollo.ast.validateAsSchema
import kotlin.test.Test
import kotlin.test.assertEquals

class GetFieldPoliciesTest {
  @Test
  fun simpleTypePolicies() {
    // language=GraphQL
    val schema = """
      type Query {
        user(id: ID!): User
        animal(kingdom: String!, species: String!): Animal
      }
      
      type User @typePolicy(keyFields: "id") {
        id: ID!
      }
      
      interface Animal @typePolicy(keyFields: "kingdom species") {
        kingdom: String!
        species: String!
      }
      
      extend type Query
      @fieldPolicy(forField: "user", keyArgs: "id")
      @fieldPolicy(forField: "animal", keyArgs: "kingdom species")
    """.trimIndent()
        .parseAsGQLDocument().getOrThrow()
        .validateAsSchema(
            SchemaValidationOptions(
                addKotlinLabsDefinitions = true,
                foreignSchemas = builtinForeignSchemas() + cacheForeignSchema
            )
        ).getOrThrow()

    val expected = mapOf(
        "Query" to FieldPolicies(
            fieldPolicies = mapOf(
                "user" to FieldPolicies.FieldPolicy(
                    keyArgs = listOf("id")
                ),
                "animal" to FieldPolicies.FieldPolicy(
                    keyArgs = listOf("kingdom", "species")
                )
            )
        )
    )

    assertEquals(expected, schema.getFieldPolicies())
  }
}
