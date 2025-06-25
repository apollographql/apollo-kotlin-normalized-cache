# Relay-style pagination

[Relay-style pagination](https://relay.dev/graphql/connections.htm) is a common way of modeling pagination in GraphQL,
where fields return `Connection`s that contain a list of `Edges`:

```graphql
type Query {
  usersConnection(first: Int = 10, after: String = null, last: Int = null, before: String = null): UserConnection!
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
}
```

```graphql
query UsersConnection($first: Int, $after: String, $last: Int, $before: String) {
  usersConnection(first: $first, after: $after, last: $last, before: $before) {
    edges {
      cursor
      node {
        name
      }
    }
    pageInfo {
      hasNextPage
      endCursor
    }
  }
}
```

If your schema uses this pagination style, the library supports it out of the box: use the `connectionFields` argument to specify the fields
that return a connection:

```graphql
extend type Query @typePolicy(connectionFields: "usersConnection")
```

In Kotlin, configure the cache like this, using the generated `Pagination` object:

```kotlin
val client = ApolloClient.Builder()
    // ...
    .normalizedCache(
        normalizedCacheFactory = cacheFactory,
        metadataGenerator = ConnectionMetadataGenerator(Pagination.connectionTypes),
        recordMerger = ConnectionRecordMerger
    )
    .build()
```

Query `UsersConnection()` to fetch new pages and update the cache, and watch it to observe the full list.

An example of doing this is available [here](https://github.com/apollographql/apollo-kotlin-samples/tree/main/normalized-cache/pagination/pagination-support).
