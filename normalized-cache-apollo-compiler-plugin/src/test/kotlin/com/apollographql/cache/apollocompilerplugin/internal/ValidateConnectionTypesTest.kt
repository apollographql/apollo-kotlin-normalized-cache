@file:OptIn(ApolloExperimental::class)
@file:Suppress("ApolloMissingGraphQLDefinitionImport", "GraphQLUnresolvedReference")

package com.apollographql.cache.apollocompilerplugin.internal

import com.apollographql.apollo.annotations.ApolloExperimental
import com.apollographql.apollo.ast.builtinForeignSchemas
import com.apollographql.apollo.ast.internal.SchemaValidationOptions
import com.apollographql.apollo.ast.parseAsGQLDocument
import com.apollographql.apollo.ast.validateAsSchema
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class ValidateConnectionTypesTest {
  @Test
  fun missingTypePolicyOnNode() {
    // language=GraphQL
    val baseSchemaText = """
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

    // language=GraphQL
    val schemaText = baseSchemaText + """
      extend schema
      @link(url: "https://specs.apollo.dev/kotlin_labs/v0.3")
      @link(url: "https://specs.apollo.dev/cache/v0.3", import: ["@connection", "@typePolicy"])
      
      extend type UserConnection @connection
    """.trimIndent()

    val schema = schemaText.parseAsGQLDocument().getOrThrow()
        .validateAsSchema(
            SchemaValidationOptions(
                addKotlinLabsDefinitions = true,
                foreignSchemas = builtinForeignSchemas() + cacheForeignSchema
            )
        )
        .getOrThrow()
    val connectionTypes = schema.getConnectionTypes()
    val typePolicies = schema.getTypePolicies()
    val errors = schema.validateConnectionTypes(connectionTypes, typePolicies)
    assertContentEquals( listOf("The type of 'UserEdge.node', 'User', must have key fields defined with @typePolicy"), errors)
  }

  @Test
  fun typePolicyOnNodeType() {
    // language=GraphQL
    val baseSchemaText = """
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

    // language=GraphQL
    val schemaText = baseSchemaText + """
      extend schema
      @link(url: "https://specs.apollo.dev/kotlin_labs/v0.3")
      @link(url: "https://specs.apollo.dev/cache/v0.3", import: ["@connection", "@typePolicy"])
      
      extend type UserConnection @connection
      extend type User @typePolicy(keyFields: "id")
    """.trimIndent()

    val schema = schemaText.parseAsGQLDocument().getOrThrow()
        .validateAsSchema(
            SchemaValidationOptions(
                addKotlinLabsDefinitions = true,
                foreignSchemas = builtinForeignSchemas() + cacheForeignSchema
            )
        )
        .getOrThrow()
    val connectionTypes = schema.getConnectionTypes()
    val typePolicies = schema.getTypePolicies()
    val errors = schema.validateConnectionTypes(connectionTypes, typePolicies)
    assertTrue(errors.isEmpty())
  }

  @Test
  fun typePolicyOnNodeInterface() {
    // language=GraphQL
    val baseSchemaText = """
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
      
      type User implements Node{
        id: ID!
        name: String!
        email: String!
        admin: Boolean
      }
      
      interface Node {
        id: ID!
      }
    """.trimIndent()

    // language=GraphQL
    val schemaText = baseSchemaText + """
      extend schema
      @link(url: "https://specs.apollo.dev/kotlin_labs/v0.3")
      @link(url: "https://specs.apollo.dev/cache/v0.3", import: ["@connection", "@typePolicy"])
      
      extend type UserConnection @connection
      extend interface Node @typePolicy(keyFields: "id")
    """.trimIndent()

    val schema = schemaText.parseAsGQLDocument().getOrThrow()
        .validateAsSchema(
            SchemaValidationOptions(
                addKotlinLabsDefinitions = true,
                foreignSchemas = builtinForeignSchemas() + cacheForeignSchema
            )
        )
        .getOrThrow()
    val connectionTypes = schema.getConnectionTypes()
    val typePolicies = schema.getTypePolicies()
    val errors = schema.validateConnectionTypes(connectionTypes, typePolicies)
    assertTrue(errors.isEmpty())
  }

  @Test
  fun missingTypePolicyOnNodeUnion() {
    // language=GraphQL
    val baseSchemaText = """
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
        node: UserUnion!
      }
      
      union UserUnion = UserA | UserB
      
      type UserA {
        id: ID!
        name: String!
      }
      
      type UserB {
        id: ID!
        name: String!
      }
    """.trimIndent()

    // language=GraphQL
    val schemaText = baseSchemaText + """
      extend schema
      @link(url: "https://specs.apollo.dev/kotlin_labs/v0.3")
      @link(url: "https://specs.apollo.dev/cache/v0.3", import: ["@connection", "@typePolicy"])
      
      extend type UserConnection @connection
      
      extend type UserA @typePolicy(keyFields: "id")
    """.trimIndent()

    val schema = schemaText.parseAsGQLDocument().getOrThrow()
        .validateAsSchema(
            SchemaValidationOptions(
                addKotlinLabsDefinitions = true,
                foreignSchemas = builtinForeignSchemas() + cacheForeignSchema
            )
        )
        .getOrThrow()
    val connectionTypes = schema.getConnectionTypes()
    val typePolicies = schema.getTypePolicies()
    val errors = schema.validateConnectionTypes(connectionTypes, typePolicies)
    assertContentEquals(listOf("The type 'UserB' which is a possible type of 'UserEdge.node' must have key fields defined with @typePolicy"), errors)
  }

  @Test
  fun typePolicyOnNodeUnion() {
    // language=GraphQL
    val baseSchemaText = """
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
        node: UserUnion!
      }
      
      union UserUnion = UserA | UserB
      
      type UserA {
        id: ID!
        name: String!
      }
      
      type UserB {
        id: ID!
        name: String!
      }
    """.trimIndent()

    // language=GraphQL
    val schemaText = baseSchemaText + """
      extend schema
      @link(url: "https://specs.apollo.dev/kotlin_labs/v0.3")
      @link(url: "https://specs.apollo.dev/cache/v0.3", import: ["@connection", "@typePolicy"])
      
      extend type UserConnection @connection
      
      extend type UserA @typePolicy(keyFields: "id")
      extend type UserB @typePolicy(keyFields: "id")
    """.trimIndent()

    val schema = schemaText.parseAsGQLDocument().getOrThrow()
        .validateAsSchema(
            SchemaValidationOptions(
                addKotlinLabsDefinitions = true,
                foreignSchemas = builtinForeignSchemas() + cacheForeignSchema
            )
        )
        .getOrThrow()
    val connectionTypes = schema.getConnectionTypes()
    val typePolicies = schema.getTypePolicies()
    val errors = schema.validateConnectionTypes(connectionTypes, typePolicies)
    assertTrue(errors.isEmpty())
  }
}
