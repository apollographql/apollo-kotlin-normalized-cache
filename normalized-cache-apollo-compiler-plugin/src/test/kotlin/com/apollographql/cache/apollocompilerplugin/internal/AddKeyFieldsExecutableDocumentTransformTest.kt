@file:OptIn(ApolloExperimental::class)
@file:Suppress("ApolloMissingGraphQLDefinitionImport", "GraphQLUnresolvedReference")

package com.apollographql.cache.apollocompilerplugin.internal

import com.apollographql.apollo.annotations.ApolloExperimental
import com.apollographql.apollo.ast.SourceAwareException
import com.apollographql.apollo.ast.internal.SchemaValidationOptions
import com.apollographql.apollo.ast.parseAsGQLDocument
import com.apollographql.apollo.ast.toGQLDocument
import com.apollographql.apollo.ast.toUtf8
import com.apollographql.apollo.ast.validateAsSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AddKeyFieldsExecutableDocumentTransformTest {
  @Test
  fun keyFieldsOnObject() {
    // language=GraphQL
    val schemaText = """
      type Query {
        user: User
      }
      
      type User @typePolicy(keyFields: "id") {
        id: ID!
        name: String!
        friends: [User!]!
      }
    """.trimIndent()

    // language=GraphQL
    val operationText = """
      query GetUser {
        user {
          name
          friends {
            name
          }
        }
      }
    """.trimIndent()

    // language=GraphQL
    val expected = """
      query GetUser {
        user {
          __typename
          name
          friends {
            __typename
            name
            id
          }
          id
        }
      }
    """.trimIndent()

    checkTransform(schemaText, operationText, expected)
  }

  @Test
  fun keyFieldsOnInterface() {
    // language=GraphQL
    val schemaText = """
      type Query {
        user: User
      }
            
      interface HasID @typePolicy(keyFields: "id") {
        id: ID!
      }
      
      interface User implements HasID {
        id: ID!
        name: String!
      }
      
      type Person implements User & HasID {
        id: ID!
        name: String!
        friends: [Person!]!
      }
      
      type Robot implements User & HasID {
        id: ID!
        name: String!
        model: String!
      }
    """.trimIndent()

    // language=GraphQL
    val operationText = """
      query GetUser {
        user {
          name
        }
      }
    """.trimIndent()

    // language=GraphQL
    val expected = """
      query GetUser {
        user {
          __typename
          name
          id
        }
      }
    """.trimIndent()

    checkTransform(schemaText, operationText, expected)
  }

  @Test
  fun keyFieldsOnUnionMembers() {
    // language=GraphQL
    val schemaText = """
      type Query {
        user: User
      }
      
      union User = Person | Robot
      
      type Person @typePolicy(keyFields: "id") {
        id: ID!
        name: String!
        friends: [Person!]!
      }
      
      type Robot @typePolicy(keyFields: "serialNumber") {
        serialNumber: ID!
        name: String!
        model: String!
      }
    """.trimIndent()

    // language=GraphQL
    val operationText = """
      query GetUser {
        user {
          ... on Person {
            friends {
              name
            }
          }
        }
      }
    """.trimIndent()

    // language=GraphQL
    val expected = """
      query GetUser {
        user {
          __typename
          ... on Person {
            friends {
              __typename
              name
              id
            }
          }
          ... on Person {
            id
          }
          ... on Robot {
            serialNumber
          }
        }
      }
    """.trimIndent()

    checkTransform(schemaText, operationText, expected)
  }

  @Test
  fun keyFieldsOnInterfacePossibleTypes() {
    // language=GraphQL
    val schemaText = """
      type Query {
        user: User
      }
      
      interface User {
        id: ID!
        name: String!
      }
      
      type Person implements User @typePolicy(keyFields: "id") {
        id: ID!
        name: String!
        friends: [Person!]!
      }
      
      interface Robot implements User @typePolicy(keyFields: "serialNumber") {
        id: ID!
        name: String!
        serialNumber: ID!
        model: String!
      }
      
      type Android implements Robot & User {
        id: ID!
        name: String!
        serialNumber: ID!
        model: String!
        osVersion: String!
      }
    """.trimIndent()

    // language=GraphQL
    val operationText = """
      query GetUser {
        user {
          ... on Person {
            friends {
              name
            }
          }
        }
      }
    """.trimIndent()

    // language=GraphQL
    val expected = """
      query GetUser {
        user {
          __typename
          ... on Person {
            friends {
              __typename
              name
              id
            }
          }
          ... on Person {
            id
          }
          ... on Android {
            serialNumber
          }
        }
      }
    """.trimIndent()

    checkTransform(schemaText, operationText, expected)
  }

  @Test
  fun aliasOnKeyField() {
    // language=GraphQL
    val schemaText = """
      type Query {
        user: User
      }
      
      type User @typePolicy(keyFields: "id") {
        id: ID!
        name: String!
      }
    """.trimIndent()

    // language=GraphQL
    val operationText = """
      query GetUser {
        user {
          name
          myId: id
        }
      }
    """.trimIndent()

    // language=GraphQL
    val expected = """
      query GetUser {
        user {
          __typename
          name
          myId: id
          id
        }
      }
    """.trimIndent()

    checkTransform(schemaText, operationText, expected)
  }


  @Test
  fun keyFieldAliasClash() {
    // language=GraphQL
    val schemaText = """
      type Query {
        user: User
      }
      
      type User @typePolicy(keyFields: "id") {
        id: ID!
        name: String!
      }
    """.trimIndent()

    // language=GraphQL
    val operationText = """
      query GetUser {
        user {
          name
          id: name
        }
      }
    """.trimIndent()

    checkTransformThrows(schemaText, operationText, expectedMessage = "e: null: (4, 5): Apollo: Field 'id: name' in User conflicts with key fields")
  }
}


private fun checkTransform(schemaText: String, operationText: String, expected: String) {
  val schema = schemaText
      .parseAsGQLDocument().getOrThrow()
      .validateAsSchema(
          SchemaValidationOptions(
              addKotlinLabsDefinitions = true,
              foreignSchemas = emptyList(),
          )
      ).getOrThrow()
  val document = operationText.toGQLDocument()
  assertEquals(expected, AddKeyFieldsExecutableDocumentTransform.transform(schema, document, emptyList()).toUtf8().trim())
}

private fun checkTransformThrows(schemaText: String, operationText: String, expectedMessage: String) {
  val schema = schemaText
      .parseAsGQLDocument().getOrThrow()
      .validateAsSchema(
          SchemaValidationOptions(
              addKotlinLabsDefinitions = true,
              foreignSchemas = emptyList(),
          )
      ).getOrThrow()
  val document = operationText.toGQLDocument()
  assertFailsWith<SourceAwareException> {
    AddKeyFieldsExecutableDocumentTransform.transform(schema, document, emptyList())
  }.apply {
    assertEquals(expectedMessage, message)
  }
}
