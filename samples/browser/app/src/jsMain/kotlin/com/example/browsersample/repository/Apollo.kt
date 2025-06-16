package com.example.browsersample.repository

import app.cash.sqldelight.driver.worker.WebWorkerDriver
import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Optional
import com.apollographql.cache.normalized.FetchPolicy
import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.fetchPolicy
import com.apollographql.cache.normalized.memory.MemoryCacheFactory
import com.apollographql.cache.normalized.sql.SqlNormalizedCacheFactory
import com.example.browsersample.BuildConfig
import com.example.browsersample.graphql.RepositoryListQuery
import com.example.browsersample.graphql.cache.Cache.cache
import org.w3c.dom.Worker

private const val SERVER_URL = "https://api.github.com/graphql"

private const val HEADER_AUTHORIZATION = "Authorization"
private const val HEADER_AUTHORIZATION_BEARER = "Bearer"

val apolloClient: ApolloClient by lazy {
  val memoryCache = MemoryCacheFactory(maxSizeBytes = 5 * 1024 * 1024)

  // Commented out as we use our own custom Worker that loads/saves the db file via OPFS.
  // See `src/jsMain/resources/sqljs.opfs.worker.js`
  // Uncomment to use the default SQLDelight worker instead, which stays in memory.
  // val sqlCache = SqlNormalizedCacheFactory()
  val sqlCache = SqlNormalizedCacheFactory(WebWorkerDriver(Worker(js("""new URL("sqljs.opfs.worker.js", import.meta.url)"""))))

  val memoryThenSqlCache = memoryCache.chain(sqlCache)

  ApolloClient.Builder()
    .serverUrl(SERVER_URL)

    // Add headers for authentication
    .addHttpHeader(
      HEADER_AUTHORIZATION,
      "$HEADER_AUTHORIZATION_BEARER ${BuildConfig.GITHUB_OAUTH_KEY}"
    )

    // Normalized cache
    .cache(
      normalizedCacheFactory = memoryThenSqlCache,
      keyScope = CacheKey.Scope.SERVICE,
    )

    .build()
}

suspend fun fetchAndMergeNextPage() {
  // 1. Get the current list from the cache
  val listQuery = RepositoryListQuery()
  val cacheResponse = apolloClient.query(listQuery).fetchPolicy(FetchPolicy.CacheOnly).execute()

  // 2. Fetch the next page from the network and store it in the cache
  val after = cacheResponse.data!!.organization!!.repositories.pageInfo.endCursor
  apolloClient.query(RepositoryListQuery(after = Optional.presentIfNotNull(after)))
    .fetchPolicy(FetchPolicy.NetworkOnly).execute()
}
