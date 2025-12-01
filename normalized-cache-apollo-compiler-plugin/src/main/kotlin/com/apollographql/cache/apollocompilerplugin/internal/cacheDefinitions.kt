@file:OptIn(ApolloExperimental::class)

package com.apollographql.cache.apollocompilerplugin.internal

import com.apollographql.apollo.annotations.ApolloExperimental
import com.apollographql.apollo.ast.ForeignSchema
import com.apollographql.apollo.ast.parseAsGQLDocument

// language=GraphQL
private val cacheDefinitions_0_3 = """
  ""${'"'}
  Defines key fields for an object or interface.
  ""${'"'}
  directive @typePolicy(
      ""${'"'}
      A selection set containing fields used to compute the cache key of an object.
      Nested selection sets are not supported. Order is important.
  
      Key fields can be defined on interfaces. In that case, the key fields apply to all sub-types and sub-types are not allowed to define their own key fields.
      If a type implements multiple interfaces with key fields, the key fields must match across all interfaces with key fields.
  
      The key fields are automatically added to the operations by the compiler plugin.
      Aliased key fields are not recognized and the compiler adds a non-aliased version of the field if that happens.
      If a type is queried through an interface/union, this may add fragments.
  
      For an example, this query:
  
      ```graphql
      query {
          product {
              price
          }
      }
      ```
  
      is turned into this one after compilation:
  
      ```graphql
      query {
          product {
              ... on Book {
                  isbn
              }
              ... on Movie {
                  id
              }
              price
          }
      }
      ```
  
      ""${'"'}
      keyFields: String! = ""
  
      ""${'"'}
      A selection set containing fields that should be embedded in their parent Record rather than being stored in their own records.
      Nested selection sets are not supported. Order is unimportant.
      ""${'"'}
      embeddedFields: String! = ""
      
      # Not part of the spec but avoids a validation error due to mismatch with the kotlin_labs version
      connectionFields: String! = ""
  ) on OBJECT | INTERFACE
  
  ""${'"'}
  Defines key arguments for a given field.
  ""${'"'}
  directive @fieldPolicy(
      ""${'"'}
      The name of the field this policy applies to.
      ""${'"'}
      forField: String!
  
      ""${'"'}
      A list of arguments used to compute the cache key of the object this field points to.
      The list is parsed as a selection set: both spaces and comas are valid separators.
      ""${'"'}
      keyArgs: String! = ""
      
      # Not part of the spec but avoids a validation error due to mismatch with the kotlin_labs version
      paginationArgs: String! = ""
  ) repeatable on OBJECT
  
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

// language=GraphQL
private val cacheDefinitions_0_4 = """
  ""${'"'}
  Defines key fields for an object or interface.
  ""${'"'}
  directive @typePolicy(
      ""${'"'}
      A selection set containing fields used to compute the cache key of an object.
      Nested selection sets are not supported. Order is important.
  
      Key fields can be defined on interfaces. In that case, the key fields apply to all sub-types and sub-types are not allowed to define their own key fields.
      If a type implements multiple interfaces with key fields, the key fields must match across all interfaces with key fields.
  
      The key fields are automatically added to the operations by the compiler plugin.
      Aliased key fields are not recognized and the compiler adds a non-aliased version of the field if that happens.
      If a type is queried through an interface/union, this may add fragments.
  
      For an example, this query:
  
      ```graphql
      query {
          product {
              price
          }
      }
      ```
  
      is turned into this one after compilation:
  
      ```graphql
      query {
          product {
              ... on Book {
                  isbn
              }
              ... on Movie {
                  id
              }
              price
          }
      }
      ```
  
      ""${'"'}
      keyFields: String! = ""
  
      # Not part of the spec but avoids a validation error due to mismatch with the kotlin_labs version
      embeddedFields: String! = ""
      
      # Not part of the spec but avoids a validation error due to mismatch with the kotlin_labs version
      connectionFields: String! = ""
  ) on OBJECT | INTERFACE
  
  ""${'"'}
  Defines key arguments for a given field.
  ""${'"'}
  directive @fieldPolicy(
      ""${'"'}
      The name of the field this policy applies to.
      ""${'"'}
      forField: String!
  
      ""${'"'}
      A list of arguments used to compute the cache key of the object this field points to.
      The list is parsed as a selection set: both spaces and comas are valid separators.
      ""${'"'}
      keyArgs: String! = ""
      
      # Not part of the spec but avoids a validation error due to mismatch with the kotlin_labs version
      paginationArgs: String! = ""
  ) repeatable on OBJECT
  
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
  
  ""${'"'}
  Configures the field to be embedded in its parent Record rather than being stored in its own record.
  ""${'"'}
  directive @embedded on FIELD_DEFINITION
  
  ""${'"'}
  Configures the field to be embedded in its parent Record rather than being stored in its own record.
  
  `@embeddedField` is the same as `@embedded` but can be used on type system extensions for services that do not own the schema like
  client services.
  
  For example:
  
  ```graphql
  # extend the schema to mark Author.book as embedded
  extend type Author @embeddedField(name: "book")
  ""${'"'}
  directive @embeddedField(
      name: String!
  ) repeatable on OBJECT
""".trimIndent()

private val cacheForeignSchema_0_3 = ForeignSchema("cache", "v0.3", cacheDefinitions_0_3.parseAsGQLDocument().getOrThrow().definitions)

private val cacheForeignSchema_0_4 = ForeignSchema("cache", "v0.4", cacheDefinitions_0_4.parseAsGQLDocument().getOrThrow().definitions)

internal val cacheForeignSchemas = listOf(cacheForeignSchema_0_3, cacheForeignSchema_0_4)
