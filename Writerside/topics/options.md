# Query options

When you execute a query, options control how the query is executed. `ApolloClient` returns an `ApolloResponse` that satisfies the options or an exceptional response otherwise (`response.exception` is not null).

## `fetchPolicy`

Fetch policy controls how and if the cache is used. The default is `CacheFirst`.

### CacheFirst

A response is fetched from the cache first. If no valid response cannot be found, the network is queried.

### CacheOnly

The response is fetched from the cache. If no valid response cannot be found, `response.exception` is set.

### NetworkOnly

The response is fetched from the network. If no valid response cannot be found, `response.exception` is set.

### NetworkFirst

A response is fetched from the network first. If no valid response cannot be found, the cache is queried.

## `allowCachedErrors`

Sets whether to allow GraphQL errors to be returned from the cache. If set to false, if any field is an Error in the cache, the returned response has a null data and a non-null exception of type `ApolloGraphQLException`.

## `allowCachedPartialResults`

Sets whether to allow partial results to be returned from the cache. If set to false, if any field is missing in the cache, the returned response has a null data and a non-null exception of type `CacheMissException`.

## `maxStale`

If stale fields are acceptable up to a certain value, you can set a maximum staleness duration. This duration is the maximum time that a stale field will be resolved without resulting in a cache miss. To set this duration, call [`.maxStale(Duration)`](https://apollographql.github.io/apollo-kotlin-normalized-cache/kdoc/normalized-cache/com.apollographql.cache.normalized/max-stale.html?query=fun%20%3CT%3E%20MutableExecutionOptions%3CT%3E.maxStale(maxStale:%20Duration):%20T) either globally on your client, or per operation:

```kotlin
val response = client.query(MyQuery())
    .fetchPolicy(FetchPolicy.CacheOnly)
    .maxStale(1.hours)
    .execute()
```

### `isStale`

With `maxStale`, it is possible to get data from the cache even if it is stale. To know if the response contains stale fields, you can check [`CacheInfo.isStale`](https://apollographql.github.io/apollo-kotlin-normalized-cache/kdoc/normalized-cache/com.apollographql.cache.normalized/-cache-info/is-stale.html):

```kotlin
if (response.cacheInfo?.isStale == true) {
  // The response contains at least one stale field
}
```