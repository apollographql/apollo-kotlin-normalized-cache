@file:OptIn(ApolloExperimental::class)
@file:Suppress("ApolloMissingGraphQLDefinitionImport", "GraphQLUnresolvedReference")

package com.apollographql.cache.apollocompilerplugin.internal

import com.apollographql.apollo.annotations.ApolloExperimental
import com.apollographql.apollo.ast.builtinForeignSchemas
import com.apollographql.apollo.ast.internal.SchemaValidationOptions
import com.apollographql.apollo.ast.parseAsGQLDocument
import com.apollographql.apollo.ast.validateAsSchema
import com.apollographql.apollo.compiler.ApolloCompiler
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


class GetEmbeddedFieldsTest {
  @Test
  fun connectionDirective() {
    // language=GraphQL
    val schemaText = baseSchemaText + """
      extend schema @link(url: "https://specs.apollo.dev/cache/v0.3", import: ["@connection"])
      
      extend type UserConnection @connection
      
      extend schema @link(url: "https://specs.apollo.dev/kotlin_labs/v0.3", import: ["@typePolicy"])
      extend type User @typePolicy(embeddedFields: "name email")
    """.trimIndent()

    val schema = schemaText.parseAsGQLDocument().getOrThrow()
        .validateAsSchema(
            SchemaValidationOptions(
                addKotlinLabsDefinitions = true,
                foreignSchemas = builtinForeignSchemas() + cacheForeignSchemas,
            ),
        )
        .getOrThrow()
    val connectionTypes = schema.getConnectionTypes()
    val errors = mutableListOf<String>()
    val embeddedFields = schema.getEmbeddedFields(
        object : ApolloCompiler.Logger {
          override fun warning(message: String) {
            errors += message
          }
        },
        connectionTypes,
    )
    val expected = mapOf(
        "Query" to EmbeddedFields(listOf("users")),
        "User" to EmbeddedFields(listOf("name", "email")),
        "UserConnection" to EmbeddedFields(listOf("edges", "pageInfo")),
    )
    assertEquals(expected, embeddedFields)
    assertEquals(emptyList(), errors)
  }

  @Test
  fun typePolicyDirective() {
    // language=GraphQL
    val schemaText = baseSchemaText + """
      extend schema @link(url: "https://specs.apollo.dev/kotlin_labs/v0.3", import: ["@typePolicy"])
      
      extend type Query @typePolicy(connectionFields: "users")
      
      extend type User @typePolicy(embeddedFields: "name email")
    """.trimIndent()

    val schema = schemaText.parseAsGQLDocument().getOrThrow()
        .validateAsSchema(
            SchemaValidationOptions(
                addKotlinLabsDefinitions = true,
                foreignSchemas = builtinForeignSchemas() + cacheForeignSchemas,
            ),
        )
        .getOrThrow()
    val connectionTypes = schema.getConnectionTypes()
    val errors = mutableListOf<String>()
    val embeddedFields = schema.getEmbeddedFields(
        object : ApolloCompiler.Logger {
          override fun warning(message: String) {
            errors += message
          }
        },
        connectionTypes,
    )
    val expected = mapOf(
        "Query" to EmbeddedFields(listOf("users")),
        "User" to EmbeddedFields(listOf("name", "email")),
        "UserConnection" to EmbeddedFields(listOf("edges", "pageInfo")),
    )
    assertEquals(expected, embeddedFields)
    assertEquals(emptyList(), errors)
  }

  @Test
  fun embeddedDirective() {
    // language=GraphQL
    val schemaText = """
      extend schema @link(url: "https://specs.apollo.dev/cache/v0.4", import: ["@embedded"])
      
      schema {
        query: Query
      }
      
      type Query {
        user: User
      }
      
      type User {
        id: ID!
        name: String! @embedded
      }
    """.trimIndent()

    val schema = schemaText.parseAsGQLDocument().getOrThrow()
        .validateAsSchema(
            SchemaValidationOptions(
                addKotlinLabsDefinitions = true,
                foreignSchemas = builtinForeignSchemas() + cacheForeignSchemas,
            ),
        )
        .getOrThrow()
    val connectionTypes = schema.getConnectionTypes()
    val errors = mutableListOf<String>()
    val embeddedFields = schema.getEmbeddedFields(
        object : ApolloCompiler.Logger {
          override fun warning(message: String) {
            errors += message
          }
        },
        connectionTypes,
    )
    val expected = mapOf(
        "User" to EmbeddedFields(listOf("name")),
    )
    assertEquals(expected, embeddedFields)
    assertEquals(emptyList(), errors)
  }

  @Test
  fun embeddedFieldDirective() {
    // language=GraphQL
    val schemaText = baseSchemaText + """
      extend schema @link(url: "https://specs.apollo.dev/kotlin_labs/v0.3", import: ["@typePolicy"])
      extend schema @link(url: "https://specs.apollo.dev/cache/v0.4", import: ["@embeddedField"])
      
      extend type Query @typePolicy(connectionFields: "users")
      
      extend type User @embeddedField(name: "name") @embeddedField(name: "email")
    """.trimIndent()

    val schema = schemaText.parseAsGQLDocument().getOrThrow()
        .validateAsSchema(
            SchemaValidationOptions(
                addKotlinLabsDefinitions = true,
                foreignSchemas = builtinForeignSchemas() + cacheForeignSchemas,
            ),
        )
        .getOrThrow()
    val connectionTypes = schema.getConnectionTypes()
    val errors = mutableListOf<String>()
    val embeddedFields = schema.getEmbeddedFields(
        object : ApolloCompiler.Logger {
          override fun warning(message: String) {
            errors += message
          }
        },
        connectionTypes,
    )
    val expected = mapOf(
        "Query" to EmbeddedFields(listOf("users")),
        "User" to EmbeddedFields(listOf("name", "email")),
        "UserConnection" to EmbeddedFields(listOf("edges", "pageInfo")),
    )
    assertEquals(expected, embeddedFields)
    assertEquals(emptyList(), errors)
  }
}
