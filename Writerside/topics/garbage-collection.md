# Garbage collection

The garbage collection feature allows to remove unused data from the cache to reduce its size.

## Stale fields

A field is considered stale if its **received date** is older than its (client controlled) max age, or if its (server controlled)
**expiration date** has passed.

See [](cache-control.md) for more information about staleness.

Stale fields can be removed from the cache by calling the `ApolloStore.removeStaleFields` function.

If all fields of a record are stale, the record itself is removed.

Note: when a record is removed, any reference to it will become a [dangling reference](#dangling-references).

## Dangling references

A **dangling reference** is a field whose value is a reference to a cache key that does not exist in the cache.

This can happen when:
- manually deleting records with [`ApolloStore.remove()`](https://apollographql.github.io/apollo-kotlin-normalized-cache-incubating/kdoc/normalized-cache-incubating/com.apollographql.cache.normalized/-apollo-store/remove.html)
- records get deleted because all their fields are [stale](#stale-fields)

Dangling references can be removed from the cache by calling the `ApolloStore.removeDanglingReferences` function.

If all fields of a record are dangling references, the record itself is removed.

Note: if a field's value is a list for which one or more elements are dangling references, the entire list is removed, which can result in
[unreachable records](#unreachable-records).

## Unreachable records

A record is **unreachable** if there exists no chain of references from the root record to it.

This can happen when:
- manually adding records with [`ApolloStore.writeFragment()`](https://apollographql.github.io/apollo-kotlin-normalized-cache-incubating/kdoc/normalized-cache-incubating/com.apollographql.cache.normalized/-apollo-store/write-fragment.html)
- manually deleting records with [`ApolloStore.remove()`](https://apollographql.github.io/apollo-kotlin-normalized-cache-incubating/kdoc/normalized-cache-incubating/com.apollographql.cache.normalized/-apollo-store/remove.html) and `cascade = false`: the deleted record could be the only one referencing others
- references get deleted because they're [stale](#stale-fields)

Unreachable records can be removed from the cache by calling the `ApolloStore.removeUnreachableRecords` function.

## `ApolloStore.garbageCollect()`

The `ApolloStore.garbageCollect()` function is a convenience to remove all stale fields, dangling references, and unreachable records from the cache.
