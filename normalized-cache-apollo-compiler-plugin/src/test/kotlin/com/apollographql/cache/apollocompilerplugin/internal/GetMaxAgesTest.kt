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
import kotlin.test.assertFailsWith
import kotlin.test.fail

class GetMaxAgesTest {
  @Test
  fun basic() {
    // language=GraphQL
    val schemaText = """
      schema {
        query: Query
      }
      
      extend schema @link(
        url: "https://specs.apollo.dev/cache/v0.3",
        import: ["@cacheControl", "@cacheControlField"]
      )
      
      type Query {
        book: Book
        cachedBook: Book @cacheControl(maxAge: 60)
        reader: Reader @cacheControl(maxAge: 40)
      }
      
      type Book {
        title: String
        cachedTitle: String @cacheControl(maxAge: 30)
      }
      
      type Reader {
        book: Book @cacheControl(inheritMaxAge: true)
      }
      
      type User @cacheControl(maxAge: 50) {
        name: String!
        email: String!
      }
      
      interface Node @cacheControl(maxAge: 10) {
        id: ID!
      }
      
      type Publisher {
        id: ID!
        name: String!
      }
      
      extend type Publisher
      @cacheControlField(name: "name", maxAge: 20)
      @cacheControlField(name: "id", inheritMaxAge: true)
    """.trimIndent()

    val maxAges = schemaText.parseAsGQLDocument().getOrThrow()
        .validateAsSchema(
            SchemaValidationOptions(
                addKotlinLabsDefinitions = true,
                foreignSchemas = builtinForeignSchemas() + cacheForeignSchemas,
            ),
        )
        .getOrThrow()
        .getMaxAges(
            object : ApolloCompiler.Logger {
              override fun warning(message: String) {
                fail()
              }
            }
        )

    val expected = mapOf(
        "Query.cachedBook" to 60,
        "Query.reader" to 40,
        "Book.cachedTitle" to 30,
        "Reader.book" to -1,
        "User" to 50,
        "Node" to 10,
        "Publisher.name" to 20,
        "Publisher.id" to -1
    )
    assertEquals(expected, maxAges)
  }

  @Test
  fun validationErrors() {
    // language=GraphQL
    val schemaText = """
      schema {
        query: Query
      }
      
      extend schema @link(
        url: "https://specs.apollo.dev/cache/v0.3",
        import: ["@cacheControl", "@cacheControlField"]
      )
      
      type Query {
        book: Book
        cachedBook: Book @cacheControl(maxAge: 60, inheritMaxAge: true)
        reader: Reader @cacheControl(maxAge: 40, inheritMaxAge: false)
      }
      
      type Book @cacheControl(maxAge: 50, inheritMaxAge: true) {
        title: String
      }
      
      type Reader @cacheControl(maxAge: 50, inheritMaxAge: false){
        book: Book
      }
      
      type Publisher {
        id: ID!
        name: String!
      }
      
      extend type Publisher
      @cacheControlField(name: "name", maxAge: 20, inheritMaxAge: true)
      @cacheControlField(name: "id", maxAge: 20, inheritMaxAge: false)
      @cacheControlField(name: "age", maxAge: 30)
      @cacheControlField(name: "id", maxAge: -4)
    """.trimIndent()

    val errors = mutableListOf<String>()

    assertFailsWith<IllegalStateException> {
      schemaText.parseAsGQLDocument().getOrThrow()
          .validateAsSchema(
              SchemaValidationOptions(
                  addKotlinLabsDefinitions = true,
                  foreignSchemas = builtinForeignSchemas() + cacheForeignSchemas,
              ),
          )
          .getOrThrow()
          .getMaxAges(
            object : ApolloCompiler.Logger {
              override fun warning(message: String) {
                errors += message
              }
            }
          )
    }
    val expectedErrors = listOf(
        "null: (12, 20): `@cacheControl` must either provide a `maxAge` or an `inheritMaxAge` set to true",
        "null: (16, 11): `@cacheControl` must either provide a `maxAge` or an `inheritMaxAge` set to true",
        "null: (30, 1): `@cacheControlField` must either provide a `maxAge` or an `inheritMaxAge` set to true",
        "null: (32, 1): Field `age` does not exist on type `Publisher`",
        "null: (33, 1): `maxAge` must not be negative"
    )
    assertEquals(expectedErrors, errors)
  }
}
