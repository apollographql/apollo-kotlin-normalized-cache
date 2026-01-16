# Next version (unreleased)

PUT_CHANGELOG_HERE

# v1.0.0-beta.3
_2026-01-16_

> Note: this release changes the SQL database schema, which clears all cached data.
> Therefore when upgrading to this version from the previous beta, cache misses are expected until the cache is repopulated.
> This was necessary to fix the `SQLiteBlobTooBigException` long standing [issue](https://github.com/apollographql/apollo-kotlin-normalized-cache/issues/121).

- Make records chunked if they are above 1MiB (#286)
- Add `ReadOnlyNormalizedCache.nextCache` and `ReadOnlyNormalizedCache.size()` (#280)
- Add `loadAllRecords` (#283)
- Add `onError` (experimental) (#284) 
- Catch `Throwable` rather than `Exception` in the SQL cache (#287)

# v1.0.0-beta.2

The artifact was published with the wrong version name, do not use.

# v1.0.0-beta.1
_2025-12-09_

- Pagination: incoming pages that cannot be merged with existing data are now ignored instead of replacing existing data. (#278)
  Incoming pages that don't include a `before` or `after` argument are still interpreted as a 'reset' signal and will replace existing data.
  This is a behavior change but hopefully not too disruptive and we believe this change makes more sense than the previous behavior.
- Add support for `@embedded` directive. This should be used in favor of `@typePolicy(embeddedFields: "...")` going forward. (#273)
- Fix sample code demonstrating how to migrate data from the legacy cache to the new library. (#274)

# v1.0.0-beta.0
_2025-11-26_

- Add a publish argument to `CacheManager`/`ApolloStore` write operations (#265)
- Add `ReadOnlyNormalizedCache.sizeOfRecord` (#118)
- Disallow the same db file name to be used multiple times at the same time (#139)
- Use a mutex in `MemoryCache` even when there is no `nextCache` (#259)
- Default to a 4 MiB cursor window size (#268)
- Add some pagination validation (#261)
- Don't crash when encountering unknown fields (#258)

# v1.0.0-alpha.8
_2025-11-06_

- Database files are now stored in the `cache` directory by default on Android (#164)
  Note: if a cache in the previous location exists, it will be used, ensuring data is not lost when upgrading to this version.
- `FieldPolicyCacheResolver` now uses the `fieldPolicies` passed to its constructor instead of the information from `CompiledField`. This allows to better isolate the main Apollo Kotlin project from the cache project. (#250)
  If your code was calling `FieldPolicyCacheResolver()` directly, update it to pass `Cache.fieldPolicies` to the constructor.
- Rename `IdCacheKeyResolver` to `IdCacheResolver`, and keep the list ids and single id arguments in the same list. (#250)
- Introduce `KeyArgumentsCacheResolver` to programmatically provide key arguments (to use when `@fieldPolicy` is not suitable or desired) (#250)
- Un-deprecate `FetchPolicy.CacheAndNetwork` (#253)
  See https://github.com/apollographql/apollo-kotlin/pull/6772 for more context.
- Treat parse errors from the cache as cache misses (#255)

# v1.0.0-alpha.7
_2025-10-14_

- Pagination API tweaks (#230)
  - Configuring connection fields with `@typePolicy(connectionFields: "...")` is deprecated. Instead, apply `@connection` to the connection types.
  - Configuring pagination arguments with `@fieldPolicy(forField: "...", paginationArgs: "...")` is deprecated. Instead, configure a `FieldKeyGenerator` on your cache.
  - The API of `EmbeddedFieldsProvider` has been tweaked to be called field by field, rather than by type.
- `@typePolicy` and `@fieldPolicy` are now in the `cache` specification (#231)
  - After a deprecation period, they will be removed from the `kotlin_labs` specification. Here is how you should import these directives in your `extra.graphqls`:
    ```graphql
    extend schema
    @link(url: "https://specs.apollo.dev/kotlin_labs/v0.5")
    @link(url: "https://specs.apollo.dev/cache/v0.3", import: ["@typePolicy", "@fieldPolicy"])
    ```

# v1.0.0-alpha.6
_2025-08-21_

- Rename `allowCachedErrors` -> `serverErrorsAsCacheMisses` and `allowCachePartialResults` -> `throwOnCacheMiss` (#214)

# v1.0.0-alpha.5
_2025-08-04_

- Add `allowCachedPartialResults()` and `allowCachedErrors()`(#205)

  These flags control whether partial results and errors should be returned from the cache. They are `false` by default.
- Return existing cached values (e.g. Errors) in `FieldPolicyCacheResolver` (#198)
- The compiler plugin's package name argument has been renamed `com.apollographql.cache.packageName`
  (was `packageName`) to avoid any collision with other plugins (#207) 

  Using `packageName` still works, but is deprecated.
- The `cache()` extension now stores received dates if max ages are configured (#199)
- Expose metadata in `cacheDumpProvider` (#200)
- Deprecate `FetchPolicy.CacheAndNetwork` (#205)

  This can be used instead: `fetchPolicy(FetchPolicy.CacheOnly).toFlow().onCompletion { emitAll(fetchPolicy(FetchPolicy.NetworkOnly).toFlow()) }`

# v1.0.0-alpha.4
_2025-06-30_

- Add browser JS support to `normalized-cache-sqlite` (#177)
  
  With this change, `NormalizedCache`/`CacheManager`/`ApolloStore` APIs are now `suspend`.
- Add `ApolloClient.Builder.cache()` extension generation (#181)
  
  This generates a convenience `cache()` extension which configures the `CacheKeyGenerator`, `MetadataGenerator`, `CacheResolver`, and `RecordMerger` based
  on the type policies, connection types, and max ages configured in the schema:
  ```kotlin
  val apolloClient = ApolloClient.Builder()
      // ...
      .cache(cacheFactory = /*...*/)
      .build()
  ```
- Add ability to control clock used for received and expiration dates, for tests (#189)
- [Breaking] Disable optimistic updates by default (#190)
  
  To use optimistic updates, apps must now opt in, by passing `enableOptimisticUpdates = true` to the `normalizedCache` method. This was done as an optimization on native targets.

# v1.0.0-alpha.3
_2025-06-06_

> With this release, the Normalized Cache requires Apollo Kotlin 4.3.0 or later.
> This is necessary to ensure the cache compiler plugin can be used while other compiler plugins are also used.

- Update Apollo compiler plugin to 4.3 API (#169)
- Make deprecations less annoying (#163)
- Don't assume presence of keyFields on `@typePolicy` (#162)
- Bump sqldelight to 2.1.0 (#167)

# v1.0.0-alpha.2
_2025-05-20_

- The computation of cache keys when multiple key fields are used has changed to avoid potential collisions. Note: this can lead to cache misses after upgrading to this version. (#80)
- Make SQL cache more robust. (#152)
- Support simple list cases in `FieldPolicyCacheResolver`. (#142)
- Fragments selecting the key fields are now automatically added for union members and interface possible types by the compiler plugin. (#141)
- Introduce `CacheKey.Scope`. (#102)

# v1.0.0-alpha.1
_2025-04-28_

- Rename `ApolloStore` to `CacheManager` and `SimpleApolloStore` to `ApolloStore`. (#134)
- Revert the `ApolloClient.apolloStore` deprecation - keeping the original name makes more sense now after the above rename. (#134)
- Add `ApolloStore.removeOperation()` and `ApolloStore.removeFragment()`. (#135)

# v0.0.9
_2025-04-09_

- Removing "incubating" from the repository and artifacts name. With a stable API on the horizon, now is a great time to try the library in your projects and give us feedback.
  The artifacts are now:
  - `com.apollographql.cache:normalized-cache` for the memory cache
  - `com.apollographql.cache:normalized-cache-sqlite` for the SQL cache.

  The package names are unchanged.
- Records are now rooted per operation type (QUERY_ROOT, MUTATION_ROOT, SUBSCRIPTION_ROOT) (#109)
- `ApolloClient.apolloStore` is deprecated in favor of `ApolloClient.store` for consistency. (#127)
- `ApolloClient.apolloStore` now returns a `SimpleApolloStore`, a wrapper around `ApolloStore` that doesn't need a `CustomScalarAdapters` to be passed to read/write methods. (#123)

# v0.0.8
_2025-03-28_

- Storage binary format is changed to be a bit more compact
- Add `ApolloStore.trim()` to remove old data from the cache
- `CacheKey` is used in more APIs instead of `String`, for consistency.
- `ApolloCacheHeaders.EVICT_AFTER_READ` is removed. `ApolloStore.remove()` can be used instead.
- `NormalizedCache.remove(pattern: String)` is removed. Please open an issue if you need this feature back.

# v0.0.7
_2025-03-03_

- Store errors in the cache, and remove `storePartialResponses()` (#96)

# v0.0.6
_2025-02-11_

- Add `ApolloStore.ALL_KEYS` to notify all watchers (#87)
- Support partial responses from the cache (#57)

# v0.0.5
_2024-12-18_

- Add Garbage Collection support (see [the documentation](https://apollographql.github.io/apollo-kotlin-normalized-cache-incubating/garbage-collection.html) for details)

# v0.0.4
_2024-11-07_

- Expiration support (see [the documentation](https://apollographql.github.io/apollo-kotlin-normalized-cache-incubating/expiration.html) for details)
- Compatibility with the IntelliJ plugin cache viewer (#42)
- For consistency, `MemoryCacheFactory` and `MemoryCache` are now in the `com.apollographql.cache.normalized.memory` package 
- Remove deprecated symbols
- Add `IdCacheKeyGenerator` and `IdCacheKeyResolver` (#41)
- Add `ApolloStore.writeOptimisticUpdates` API for fragments (#55)

# v0.0.3
_2024-09-20_

Tweaks to the `ApolloResolver` API: `resolveField()` now takes a `ResolverContext`

# v0.0.2
_2024-07-08_

Update to Apollo Kotlin 4.0.0-rc.1

# v0.0.1
_2024-06-20_

Initial release
