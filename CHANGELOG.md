# Next version (unreleased)

- The computation of cache keys when multiple key fields are used has changed to avoid potential collisions. Note: this can lead to cache misses after upgrading to this version. (#80)
- Make SQL cache more robust. (#152)
- Support simple list cases in `FieldPolicyCacheResolver`. (#142)
- Fragments selecting the key fields are now automatically added for union members and interface possible types by the compiler plugin. (#141)
- Introduce `CacheKey.Scope`. (#102)

# Version 1.0.0-alpha.1

- Rename `ApolloStore` to `CacheManager` and `SimpleApolloStore` to `ApolloStore`. (#134)
- Revert the `ApolloClient.apolloStore` deprecation - keeping the original name makes more sense now after the above rename. (#134)
- Add `ApolloStore.removeOperation()` and `ApolloStore.removeFragment()`. (#135)

# Version 0.0.9
_2025-04-09_

- Removing "incubating" from the repository and artifacts name. With a stable API on the horizon, now is a great time to try the library in your projects and give us feedback.
  The artifacts are now:
  - `com.apollographql.cache:normalized-cache` for the memory cache
  - `com.apollographql.cache:normalized-cache-sqlite` for the SQL cache.

  The package names are unchanged.
- Records are now rooted per operation type (QUERY_ROOT, MUTATION_ROOT, SUBSCRIPTION_ROOT) (#109)
- `ApolloClient.apolloStore` is deprecated in favor of `ApolloClient.store` for consistency. (#127)
- `ApolloClient.apolloStore` now returns a `SimpleApolloStore`, a wrapper around `ApolloStore` that doesn't need a `CustomScalarAdapters` to be passed to read/write methods. (#123)

# Version 0.0.8
_2025-03-28_

- Storage binary format is changed to be a bit more compact
- Add `ApolloStore.trim()` to remove old data from the cache
- `CacheKey` is used in more APIs instead of `String`, for consistency.
- `ApolloCacheHeaders.EVICT_AFTER_READ` is removed. `ApolloStore.remove()` can be used instead.
- `NormalizedCache.remove(pattern: String)` is removed. Please open an issue if you need this feature back.

# Version 0.0.7
_2025-03-03_

- Store errors in the cache, and remove `storePartialResponses()` (#96)

# Version 0.0.6
_2025-02-11_

- Add `ApolloStore.ALL_KEYS` to notify all watchers (#87)
- Support partial responses from the cache (#57)

# Version 0.0.5
_2024-12-18_

- Add Garbage Collection support (see [the documentation](https://apollographql.github.io/apollo-kotlin-normalized-cache-incubating/garbage-collection.html) for details)

# Version 0.0.4
_2024-11-07_

- Cache control support (see [the documentation](https://apollographql.github.io/apollo-kotlin-normalized-cache-incubating/cache-control.html) for details)
- Compatibility with the IntelliJ plugin cache viewer (#42)
- For consistency, `MemoryCacheFactory` and `MemoryCache` are now in the `com.apollographql.cache.normalized.memory` package 
- Remove deprecated symbols
- Add `IdCacheKeyGenerator` and `IdCacheKeyResolver` (#41)
- Add `ApolloStore.writeOptimisticUpdates` API for fragments (#55)

# Version 0.0.3
_2024-09-20_

Tweaks to the `ApolloResolver` API: `resolveField()` now takes a `ResolverContext`

# Version 0.0.2
_2024-07-08_

Update to Apollo Kotlin 4.0.0-rc.1

# Version 0.0.1
_2024-06-20_

Initial release
