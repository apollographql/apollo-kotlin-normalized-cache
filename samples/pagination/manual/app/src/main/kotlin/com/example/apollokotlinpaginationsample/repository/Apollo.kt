package com.example.apollokotlinpaginationsample.repository

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Optional
import com.apollographql.cache.normalized.FetchPolicy
import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.api.TypePolicyCacheKeyGenerator
import com.apollographql.cache.normalized.apolloStore
import com.apollographql.cache.normalized.fetchPolicy
import com.apollographql.cache.normalized.memory.MemoryCacheFactory
import com.apollographql.cache.normalized.normalizedCache
import com.apollographql.cache.normalized.sql.SqlNormalizedCacheFactory
import com.example.apollokotlinpaginationsample.Application
import com.example.apollokotlinpaginationsample.BuildConfig
import com.example.apollokotlinpaginationsample.graphql.RepositoryListQuery
import com.example.apollokotlinpaginationsample.graphql.cache.Cache

private const val SERVER_URL = "https://api.github.com/graphql"

private const val HEADER_AUTHORIZATION = "Authorization"
private const val HEADER_AUTHORIZATION_BEARER = "Bearer"

val apolloClient: ApolloClient by lazy {
    val memoryCache = MemoryCacheFactory(maxSizeBytes = 5 * 1024 * 1024)
    val sqlCache = SqlNormalizedCacheFactory(Application.applicationContext, "app.db")
    val memoryThenSqlCache = memoryCache.chain(sqlCache)

    ApolloClient.Builder()
        .serverUrl(SERVER_URL)

        // Add headers for authentication
        .addHttpHeader(
            HEADER_AUTHORIZATION,
            "$HEADER_AUTHORIZATION_BEARER ${BuildConfig.GITHUB_OAUTH_KEY}"
        )

        // Normalized cache
        .normalizedCache(
            memoryThenSqlCache,
            cacheKeyGenerator = TypePolicyCacheKeyGenerator(
                typePolicies = Cache.typePolicies,
                keyScope = CacheKey.Scope.SERVICE,
            ),
        )

        .build()
}

suspend fun fetchAndMergeNextPage() {
    // 1. Get the current list from the cache
    val listQuery = RepositoryListQuery()
    val cacheResponse = apolloClient.query(listQuery).fetchPolicy(FetchPolicy.CacheOnly).execute()

    // 2. Fetch the next page from the network (don't update the cache yet)
    val after = cacheResponse.data!!.organization!!.repositories.pageInfo.endCursor
    val networkResponse =
        apolloClient.query(RepositoryListQuery(after = Optional.presentIfNotNull(after)))
            .fetchPolicy(FetchPolicy.NetworkOnly).execute()

    // 3. Merge the next page with the current list
    val mergedList =
        cacheResponse.data!!.organization!!.repositories.edges!! + networkResponse.data!!.organization!!.repositories.edges!!
    val dataWithMergedList = networkResponse.data!!.copy(
        organization = networkResponse.data!!.organization!!.copy(
            repositories = networkResponse.data!!.organization!!.repositories.copy(
                pageInfo = networkResponse.data!!.organization!!.repositories.pageInfo,
                edges = mergedList
            )
        )
    )

    // 4. Update the cache with the merged list
    apolloClient.apolloStore.writeOperation(operation = listQuery, data = dataWithMergedList)
        .also { keys ->
            apolloClient.apolloStore.publish(keys)
        }
}
