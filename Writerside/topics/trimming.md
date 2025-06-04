# Trimming the cache

By default, if you don't use [cache control](cache-control.md) and [garbage collection](garbage-collection.md),
the cache will grow indefinitely as more data is written to it.

To prevent this, a few APIs are available:

- [`ApolloStore.clearAll()`](https://apollographql.github.io/apollo-kotlin-normalized-cache/kdoc/normalized-cache/com.apollographql.cache.normalized/-apollo-store/index.html#-1013497887%2FFunctions%2F-1172623753):
  clear the entire cache.
- [`ApolloStore.remove()`](https://apollographql.github.io/apollo-kotlin-normalized-cache/kdoc/normalized-cache/com.apollographql.cache.normalized/-apollo-store/index.html#-1351099158%2FFunctions%2F-1172623753):
  remove specific records.
- [`ApolloStore.removeOperation()`](https://apollographql.github.io/apollo-kotlin-normalized-cache/kdoc/normalized-cache/com.apollographql.cache.normalized/remove-operation.html?query=fun%20%3CD%20:%20Operation.Data%3E%20ApolloStore.removeOperation(operation:%20Operation%3CD%3E,%20data:%20D,%20cacheHeaders:%20CacheHeaders%20=%20CacheHeaders.NONE):%20Set%3CString%3E) and [
  `ApolloStore.removeFragment()`](https://apollographql.github.io/apollo-kotlin-normalized-cache/kdoc/normalized-cache/com.apollographql.cache.normalized/remove-fragment.html?query=fun%20%3CD%20:%20Fragment.Data%3E%20ApolloStore.removeFragment(fragment:%20Fragment%3CD%3E,%20cacheKey:%20CacheKey,%20data:%20D,%20cacheHeaders:%20CacheHeaders%20=%20CacheHeaders.NONE):%20Set%3CString%3E):
  remove the records associated to specific operations or fragments.
- [`ApolloStore.trim()`](https://apollographql.github.io/apollo-kotlin-normalized-cache/kdoc/normalized-cache/com.apollographql.cache.normalized/-cache-manager/trim.html):
  trim the cache by a specified amount (by default 10%) if it exceeds a certain size. The oldest (according to their updated date) records are removed. 
