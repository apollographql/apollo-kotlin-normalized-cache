package com.apollographql.cache.apollocompilerplugin.internal

import com.apollographql.apollo.annotations.ApolloExperimental
import com.apollographql.apollo.ast.ForeignSchema
import com.apollographql.apollo.ast.parseAsGQLDocument

private val cacheDefinitions_0_3 = """
  ""${'"'}
  Possible values for the `@cacheControl` `scope` argument (unused on the client).
  ""${'"'}
  enum CacheControlScope {
      PUBLIC
      PRIVATE
  }
  
  ""${'"'}
  Configures cache settings for a field or type.
  
  - `maxAge`: The maximum amount of time the field's cached value is valid, in seconds.
  - `inheritMaxAge`: If true, the field inherits the `maxAge` of its parent field. If set to `true`, `maxAge` must not be provided.
  - `scope`: Unused on the client.
  
  When applied to a type, the settings apply to all schema fields that return this type.
  Field-level settings override type-level settings.
  
  For example:
  
  ```graphql
  type Query {
      me: User
      user(id: ID!): User @cacheControl(maxAge: 5)
  }
  
  type User @cacheControl(maxAge: 10) {
      id: ID!
      email: String
  }
  ```
  `Query.me` is valid for 10 seconds, and `Query.user` for 5 seconds.
  ""${'"'}
  directive @cacheControl(
      maxAge: Int
      inheritMaxAge: Boolean
      scope: CacheControlScope
  ) on FIELD_DEFINITION | OBJECT | INTERFACE | UNION | QUERY | MUTATION | SUBSCRIPTION | FRAGMENT_DEFINITION
  
  ""${'"'}
  Configures cache settings for a field.
  
  `@cacheControlField` is the same as `@cacheControl` but can be used on type system extensions for services that do not own the schema like
  client services.
  
  For example:
  
  ```graphql
  # extend the schema to set a max age on User.email.
  extend type User @cacheControlField(name: "email", maxAge: 20)
  ```
  `User.email` is valid for 20 seconds.
  ""${'"'}
  directive @cacheControlField(
      name: String!
      maxAge: Int
      inheritMaxAge: Boolean
      scope: CacheControlScope
  ) repeatable on OBJECT | INTERFACE
  
  ""${'"'}
  Configures the deletion of the child object(s) when the parent object is deleted from the cache.
  Must not be applied on scalar (or list of scalar) fields.
  - `cascade`: If true, the child object(s) will be deleted from the cache when the parent object is deleted. 
  
  For example:
  
  ```graphql
  type Author {
    id: ID!
    firstName: String!
    lastName: String!
    book: Book! @onDelete(cascade: true)
  }
  
  type Book {
    id: ID!
    title: String!
  }
  ```
  Whenever `Author` is deleted from the cache, the associated `Book` is also deleted.
  ""${'"'}
  directive @onDelete(
      cascade: Boolean!
  ) on FIELD_DEFINITION
  
  ""${'"'}
  Configures the deletion of the child object(s) when the parent object is deleted from the cache.
  Must not be applied on scalar (or list of scalar) fields.
  - `cascade`: If true, the child object(s) will be deleted from the cache when the parent object is deleted. 
  
  `@onDeleteField` is the same as `@onDelete` but can be used on type system extensions for services that do not own the schema like
  client services.
  
  For example:
  
  ```graphql
  # extend the schema to set cascading deletion on Author.book
  extend type Author @onDeleteField(name: "book", cascade: true)
  ```
  Whenever `Author` is deleted from the cache, the associated `Book` is also deleted.
  ""${'"'}
  directive @onDeleteField(
      name: String!
      cascade: Boolean!
  ) repeatable on OBJECT | INTERFACE

  ""${'"'}
  Marks the type as a pagination connection type, as defined by the [Relay Cursor Connections Specification](https://relay.dev/graphql/connections.htm#sec-Connection-Types).
  ""${'"'}
  directive @connection on OBJECT
""".trimIndent()

@OptIn(ApolloExperimental::class)
internal val cacheForeignSchema = ForeignSchema("cache", "v0.3", cacheDefinitions_0_3.parseAsGQLDocument().getOrThrow().definitions)
