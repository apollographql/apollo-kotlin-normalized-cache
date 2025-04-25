# Managing pagination manually

Using the [`ApolloStore`](https://apollographql.github.io/apollo-kotlin-normalized-cache/kdoc/normalized-cache/com.apollographql.cache.normalized/-apollo-store/index.html)
APIs, you can update the cache manually whenever you fetch a new page of data.

Here's a general outline of how you can do this:

```kotlin
suspend fun fetchAndMergePage(nextPage: Int) {
  // 1. Get the current list from the cache
  val listQuery = UsersPageQuery(page = 1)
  val cacheResponse = apolloClient.query(listQuery).fetchPolicy(FetchPolicy.CacheOnly).execute()

  // 2. Fetch the next page from the network (don't update the cache yet)
  val networkResponse = apolloClient.query(UsersPageQuery(page = nextPage)).fetchPolicy(FetchPolicy.NetworkOnly).execute()

  // 3. Merge the next page with the current list
  val mergedList = cacheResponse.data.usersPage.items + networkResponse.data.usersPage.items
  val dataWithMergedList = networkResponse.data.copy(
      usersPage = networkResponse.data.usersPage.copy(
          items = mergedList
      )
  )

  // 4. Update the cache with the merged list
  val keys = apolloClient.cacheManager.writeOperation(operation = listQuery, operationData = dataWithMergedList)
  apolloClient.cacheManager.publish(keys)
}
```

Note that in this simple example, we need to remember the last fetched page, so we can know which page to fetch next.
This can be stored in shared preferences for instance. However in most cases the API can return a "page info" object containing the information needed to fetch the next page, and this can be stored
in the cache with the rest of the data.

An example of doing this is available [here](https://github.com/apollographql/apollo-kotlin-normalized-cache/tree/main/samples/pagination/manual).
