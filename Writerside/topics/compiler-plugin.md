# Compiler plugin

When setting up the Normalized Cache in your project, you need to configure the compiler plugin:

```kotlin
// build.gradle.kts
apollo {
  service("service") {
    // ...

    // Add this
    plugin("com.apollographql.cache:normalized-cache-apollo-compiler-plugin:%latest_version%") {
      argument("packageName", packageName.get())
    }
  }
}
```

This plugin generates some code to support the Normalized Cache features, such as declarative cache IDs, pagination and cache control.

## Declarative cache IDs (`@typePolicy`)

You can refer to the [declarative cache IDs documentation](https://www.apollographql.com/docs/kotlin/caching/declarative-ids)
for a general overview of this feature.

Here are some additional details of what the compiler plugin does to support it.

Let's consider this schema for example:

```graphql
# schema.graphqls
type Query {
  user(id: ID!): User
}

type User {
  id: ID!
  email: String!
  name: String!
}
```

```graphql
# extra.graphqls
extend type User @typePolicy(keyFields: "id")
```

### Generation of `typePolicies`

A map of type names to `TypePolicy` instances is generated in a `Cache` object.

In the example above, the generated code will look like this:

```kotlin
object cache {
  val typePolicies: Map<String, TypePolicy> = mapOf(
      "User" to TypePolicy(keyFields = setOf("id"))
  )
}

```

Pass this map to the `TypePolicyCacheKeyGenerator` when configuring the cache:

```kotlin
val apolloClient = ApolloClient.Builder()
    // ...
    .normalizedCache(
        // ...
        cacheKeyGenerator = TypePolicyCacheKeyGenerator(Cache.typePolicies)
    )
    .build()
```

### Addition of key fields and `__typename` to selections

The compiler automatically adds the key fields declared with `@typePolicy` to the selections that return that type.
This is to ensure that a `CacheKey` can be generated for the record.

When you query for `User`, e.g.:

```graphql
# operations.graphql
query User {
  user(id: "1") {
    email
    name
  }
}
```

The compiler plugin will automatically add the `id` and `__typename` fields to the selection set, resulting in:

```graphql
query User {
  user(id: "1") {
    __typename # Added by the compiler plugin
    email
    name
    id # Added by the compiler plugin
  }
}
```

Now, [TypePolicyCacheKeyGenerator](https://apollographql.github.io/apollo-kotlin-normalized-cache/kdoc/normalized-cache/com.apollographql.cache.normalized.api/-type-policy-cache-key-generator.html?query=fun%20TypePolicyCacheKeyGenerator(typePolicies:%20Map%3CString,%20TypePolicy%3E,%20keyScope:%20CacheKey.Scope%20=%20CacheKey.Scope.TYPE):%20CacheKeyGenerator)
can use the value of `__typename` as the type of the returned object, and from that see that there is one key field, `id`, for that type.

From that it can return `User:42` as the cache key for that record.

> If your schema has ids that are unique across the service, you can pass `CacheKey.Scope.SERVICE` to the `TypePolicyCacheKeyGenerator` constructor to save space in the cache.
>
> In that example the cache key would be `42` instead of `User:42`.

#### Unions and interfaces

Let's consider this example:

```graphql
# schema.graphqls
type Query {
  search(text: String!): [SearchResult!]!
}

type Product {
  shopId: String!
  productId: String!
  description: String!
}

type Book {
  isbn: ID!
  title: String!
}

union SearchResult = User | Post
```

```graphql
# extra.graphqls
extend type Product @typePolicy(keyFields: "shopId productId")
extend type Book @typePolicy(keyFields: "isbn")
```

```graphql
# operations.graphql
query Search($text: String!) {
  search(text: $text) {
    ... on Book {
      title
    }
  }
}
```

The plugin needs to add the key fields of all possible types of `SearchResult`, like so:

```graphql
query Search($text: String!) {
  search(text: $text) {
    __typename # Added by the compiler plugin
    ... on Book {
      title
    }
    # Added by the compiler plugin
    ... on Book {
      isbn
    }
    ... on Product {
      shopId
      productId
    }
  }
}
```

The principle is the same with interfaces, for instance:

```graphql
# schema.graphqls
type Query {
  search(text: String!): [SearchResult!]!
}

interface SearchResult {
  summary: String!
}

type Product implements SearchResult {
  summary: String!
  shopId: String!
  productId: String!
}

type Book implements SearchResult {
  summary: String!
  isbn: ID!
  title: String!
}
```

The modified query would look the same as above, with the key fields of `Product` and `Book` added to the selection set.

> If key fields are defined on the interface itself, they only need to be added once, instead of once per possible type.

## Resolving to cache keys (`@fieldPolicy`)

When a field returns a type that has key fields, and takes arguments that correspond to these keys, you can use the `@fieldPolicy` directive.

For instance,

```graphql
# schema.graphqls
type Query {
  user(id: ID!): User
}

type User {
  id: ID!
  email: String!
  name: String!
}
```

```graphql
# extra.graphqls
extend type User @typePolicy(keyFields: "id")

extend type Query @fieldPolicy(forField: "user", keyArgs: "id")
```

From this, when selecting e.g. `user(id: 42)` the `FieldPolicyCacheResolver` knows to return `User:42` as a `CacheKey`,
thus saving a network request if the record is already in the cache.

#### Unions and interfaces {id="field-policy-unions-and-interfaces"}

If a field returns a union or interface it is not possible to know which concrete type will be returned at runtime, and thus prefixing
the cache key with the correct type name is not possible. Avoiding a network call is not possible here.

However, if your schema has ids that are unique across the service, you can pass `CacheKey.Scope.SERVICE` to the `FieldPolicyCacheResolver` constructor to skip the type name in the cache key.
Network call avoidance will still work in that case.

