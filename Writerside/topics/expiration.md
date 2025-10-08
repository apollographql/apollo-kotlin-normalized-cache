# Expiration

The cache can be configured to store expiration information using a max-age. This is also sometimes referred to as TTL (Time To Live) or freshness.

Max-age can be configured by the server, by the client, or both.

## Server-controlled max-age

When receiving a response from the server, the [`Cache-Control` HTTP header](https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Cache-Control) can be used to determine the **max age** of the fields in the response.

> Apollo Server can be configured to include the `Cache-Control` header in responses. See the [caching documentation](https://www.apollographql.com/docs/apollo-server/performance/caching/) for more information.

> The [`Expires` HTTP header](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Expires) is not supported. Only `Cache-Control` is.
{style="note"}

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

**Expiration dates** are stored and when a field is resolved, the cache resolver will check if the field is stale. If so, it will return an error..

## Client-controlled max-age

When storing fields, the cache can also store their **received date**. This date can then be compared to the current date when resolving a field to determine if its age is above its **max age**.

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

The **max age** of fields can be configured either programmatically, or declaratively in the schema. This is done by passing a [`MaxAgeProvider`](https://apollographql.github.io/apollo-kotlin-normalized-cache/kdoc/normalized-cache/com.apollographql.cache.normalized.api/-max-age-provider/index.html?query=interface%20MaxAgeProvider) to the `CacheControlCacheResolver`.

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
  url: "https://specs.apollo.dev/cache/v0.3",
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
