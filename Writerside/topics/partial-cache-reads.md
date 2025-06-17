# Partial cache reads

The cache supports partial cache reads, in a similar way to how GraphQL supports partial responses.
This means that if some fields are missing from the cache, the cache will return the available data along with any errors for the missing fields.

## With `ApolloStore`

The [`ApolloStore.readOperation()`](https://apollographql.github.io/apollo-kotlin-normalized-cache/kdoc/normalized-cache/com.apollographql.cache.normalized/-apollo-store/read-operation.html)
API returns an `ApolloResponse<D>` that can contain partial `data` and non-empty `errors` for any missing (or stale) fields in the cache.

> `ApolloResponse.cacheInfo.isCacheHit` will be false when any field is missing.
> `ApolloResponse.cacheInfo.isStale` will be true when any field is stale.

If a cache miss exception is preferred, you can use the `ApolloResponse<D>.errorsAsException()` extension function that returns 
a response with an exception if there are any errors.

## With `ApolloClient`

When executing operations, the built-in fetch policies ([`FetchPolicy.CacheFirst`](https://apollographql.github.io/apollo-kotlin-normalized-cache/kdoc/normalized-cache/com.apollographql.cache.normalized/-fetch-policy/-cache-first/index.html?query=CacheFirst),
[`FetchPolicy.CacheOnly`](https://apollographql.github.io/apollo-kotlin-normalized-cache/kdoc/normalized-cache/com.apollographql.cache.normalized/-fetch-policy/-cache-only/index.html),
etc.) will treat cache misses as exceptions (partial results are disabled).

To benefit from partial cache reads, implement your own fetch policy interceptor as shown in this example:

```kotlin
object PartialCacheOnlyInterceptor : ApolloInterceptor {
  override fun <D : Operation.Data> intercept(
      request: ApolloRequest<D>, 
      chain: ApolloInterceptorChain
  ): Flow<ApolloResponse<D>> {
    return chain.proceed(
        request = request
            .newBuilder()
            // Controls where to read the data from (cache or network)
            .fetchFromCache(true)
            .build()
    )
  }
}

// ...

val apolloClient = ApolloClient.Builder()
    /*...*/
    .serverUrl("https://example.com/graphql")
    .fetchPolicyInterceptor(PartialCacheOnlyInterceptor)
    .build()
```

## Error stored in the cache

Errors from the server are stored in the cache, and will be returned when reading it.

By default, errors don't replace existing data in the cache. You can change this behavior with [`errorsReplaceCachedValues(true)`](https://apollographql.github.io/apollo-kotlin-normalized-cache/kdoc/normalized-cache/com.apollographql.cache.normalized/errors-replace-cached-values.html?query=fun%20%3CT%3E%20MutableExecutionOptions%3CT%3E.errorsReplaceCachedValues(errorsReplaceCachedValues:%20Boolean):%20T).
