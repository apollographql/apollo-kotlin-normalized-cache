@file:OptIn(ApolloExperimental::class)

package com.apollographql.cache.apollocompilerplugin.internal

import com.apollographql.apollo.annotations.ApolloExperimental
import com.apollographql.apollo.ast.builtinForeignSchemas
import com.apollographql.apollo.ast.internal.SchemaValidationOptions
import com.apollographql.apollo.ast.parseAsGQLDocument
import com.apollographql.apollo.ast.validateAsSchema
import kotlin.test.Test
import kotlin.test.assertEquals

// language=GraphQL
private val baseSchemaText = """
  schema {
    query: Query
  }
  
  type Query {
    users(first: Int = 10, after: String = null, last: Int = null, before: String = null): UserConnection
  }
  
  type UserConnection {
    pageInfo: PageInfo!
    edges: [UserEdge!]!
  }
  
  type PageInfo {
    hasNextPage: Boolean!
    hasPreviousPage: Boolean!
    startCursor: String
    endCursor: String
  }
  
  type UserEdge {
    cursor: String!
    node: User!
  }
  
  type User {
    id: ID!
    name: String!
    email: String!
    admin: Boolean
  }

""".trimIndent()


class GetConnectionTypesTest {
  @Test
  fun connectionDirective() {
    // language=GraphQL
    val schemaText = baseSchemaText + """
      extend schema @link(url: "https://specs.apollo.dev/cache/v0.3", import: ["@connection"])
      
      extend type UserConnection @connection
    """.trimIndent()

    val connectionTypes = schemaText.parseAsGQLDocument().getOrThrow()
        .validateAsSchema(
            SchemaValidationOptions(
                addKotlinLabsDefinitions = true,
                foreignSchemas = builtinForeignSchemas() + cacheForeignSchema
            )
        )
        .getOrThrow()
        .getConnectionTypes()

    val expected = setOf(
        "UserConnection"
    )
    assertEquals(expected, connectionTypes)
  }

  @Test
  fun typePolicyDirective() {
    // language=GraphQL
    val schemaText = baseSchemaText + """
      extend schema @link(url: "https://specs.apollo.dev/kotlin_labs/v0.3", import: ["@typePolicy"])
      
      extend type Query @typePolicy(connectionFields: "users")
    """.trimIndent()

    val connectionTypes = schemaText.parseAsGQLDocument().getOrThrow()
        .validateAsSchema(
            SchemaValidationOptions(
                addKotlinLabsDefinitions = true,
                foreignSchemas = builtinForeignSchemas() + cacheForeignSchema
            )
        )
        .getOrThrow()
        .getConnectionTypes()

    val expected = setOf(
        "UserConnection"
    )
    assertEquals(expected, connectionTypes)
  }

}
