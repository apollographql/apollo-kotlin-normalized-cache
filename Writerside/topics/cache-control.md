# Cache control

The cache control feature takes the freshness of fields into consideration when accessing the cache. This is also sometimes referred to as TTL (Time To Live) or expiration.

Freshness can be configured by the server, by the client, or both.

## Server-controlled

When receiving a response from the server, the [`Cache-Control` HTTP header](https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Cache-Control) can be used to determine the **expiration date** of the fields in the response.

> Apollo Server can be configured to include the `Cache-Control` header in responses. See the [caching documentation](https://www.apollographql.com/docs/apollo-server/performance/caching/) for more information.

The cache can be configured to store the **expiration date** of the received fields in the corresponding records. To do so, call [`.storeExpirationDate(true)`](https://apollographql.github.io/apollo-kotlin-normalized-cache/kdoc/normalized-cache/com.apollographql.cache.normalized/store-expiration-date.html?query=fun%20%3CT%3E%20MutableExecutionOptions%3CT%3E.storeExpirationDate(storeExpirationDate:%20Boolean):%20T), and set your client's cache resolver to [
`CacheControlCacheResolver`](https://apollographql.github.io/apollo-kotlin-normalized-cache/kdoc/normalized-cache/com.apollographql.cache.normalized.api/-cache-control-cache-resolver/index.html):

```kotlin
val apolloClient = ApolloClient.builder()
  .serverUrl("https://example.com/graphql")
  .storeExpirationDate(true)
  .normalizedCache(
    normalizedCacheFactory = /*...*/,
    cacheResolver = CacheControlCacheResolver(),
  )
  .build()
```

**Expiration dates** will be stored and when a field is resolved, the cache resolver will check if the field is stale. If so, it will throw a `CacheMissException`.

## Client-controlled

When storing fields, the cache can also store their **received date**. This date can then be compared to the current date when resolving a field to determine if its age is above its **maximum age**.

To store the **received date** of fields, call [`.storeReceivedDate(true)`](https://apollographql.github.io/apollo-kotlin-normalized-cache/kdoc/normalized-cache/com.apollographql.cache.normalized/store-receive-date.html?query=fun%20%3CT%3E%20MutableExecutionOptions%3CT%3E.storeReceivedDate(storeReceivedDate:%20Boolean):%20T), and set your client's cache resolver to [
`CacheControlCacheResolver`](https://apollographql.github.io/apollo-kotlin-normalized-cache/kdoc/normalized-cache/com.apollographql.cache.normalized.api/-cache-control-cache-resolver/index.html):

```kotlin
val apolloClient = ApolloClient.builder()
  .serverUrl("https://example.com/graphql")
  .storeReceivedDate(true)
  .normalizedCache(
    normalizedCacheFactory = /*...*/,
    cacheResolver = CacheControlCacheResolver(maxAgeProvider),
  )
  .build()
```

> Expiration dates and received dates can be both stored to combine server-controlled and client-controlled expiration strategies.

The **maximum age** of fields can be configured either programmatically, or declaratively in the schema. This is done by passing a [`MaxAgeProvider`](https://apollographql.github.io/apollo-kotlin-normalized-cache/kdoc/normalized-cache/com.apollographql.cache.normalized.api/-max-age-provider/index.html?query=interface%20MaxAgeProvider) to the `CacheControlCacheResolver`.

### Global max age

To set a global maximum age for all fields, pass a [`GlobalMaxAgeProvider`](https://apollographql.github.io/apollo-kotlin-normalized-cache/kdoc/normalized-cache/com.apollographql.cache.normalized.api/-global-max-age-provider/index.html?query=class%20GlobalMaxAgeProvider(maxAge:%20Duration)%20:%20MaxAgeProvider) to the `CacheControlCacheResolver`:

```kotlin
    cacheResolver = CacheControlCacheResolver(GlobalMaxAgeProvider(1.hours)),
```

### Max age per type and field

#### Programmatically

Use a [`SchemaCoordinatesMaxAgeProvider`](https://apollographql.github.io/apollo-kotlin-normalized-cache/kdoc/normalized-cache/com.apollographql.cache.normalized.api/-schema-coordinates-max-age-provider/index.html?query=class%20SchemaCoordinatesMaxAgeProvider(maxAges:%20Map%3CString,%20MaxAge%3E,%20defaultMaxAge:%20Duration)%20:%20MaxAgeProvider) to specify a max age per type and/or field:

```kotlin
cacheResolver = CacheControlCacheResolver(
  SchemaCoordinatesMaxAgeProvider(
    maxAges = mapOf(
      "Query.cachedBook" to MaxAge.Duration(60.seconds),
      "Query.reader" to MaxAge.Duration(40.seconds),
      "Post" to MaxAge.Duration(4.minutes),
      "Book.cachedTitle" to MaxAge.Duration(30.seconds),
      "Reader.book" to MaxAge.Inherit,
    ), 
    defaultMaxAge = 1.hours,
  )
),
```

Note that this provider replicates the behavior of Apollo Server's [`@cacheControl` directive](https://www.apollographql.com/docs/apollo-server/performance/caching/#default-maxage) when it comes to defaults and the meaning of `Inherit`.

#### Declaratively

To declare the maximum age of types and fields in the schema, use the `@cacheControl` and `@cacheControlField` directive:

```
# First import the directives
extend schema @link(
  url: "https://specs.apollo.dev/cache/v0.1",
  import: ["@cacheControl", "@cacheControlField"]
)

# Then extend your types
extend type Query @cacheControl(maxAge: 60)
  @cacheControlField(name: "cachedBook", maxAge: 60)
  @cacheControlField(name: "reader", maxAge: 40)

extend type Post @cacheControl(maxAge: 240)

extend type Book @cacheControlField(name: "cachedTitle", maxAge: 30)

extend type Reader @cacheControlField(name: "book", inheritMaxAge: true)
```

This generates a map in `yourpackage.cache.Cache.maxAges`, that you can pass to the `SchemaCoordinatesMaxAgeProvider`:

```kotlin
cacheResolver = CacheControlCacheResolver(
  SchemaCoordinatesMaxAgeProvider(
    maxAges = Cache.maxAges,
    defaultMaxAge = 1.hours,
  )
),
```

## Maximum staleness

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
