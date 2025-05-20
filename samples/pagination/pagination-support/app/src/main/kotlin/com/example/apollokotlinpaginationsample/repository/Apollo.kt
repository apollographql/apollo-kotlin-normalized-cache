package com.example.apollokotlinpaginationsample.repository

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Optional
import com.apollographql.cache.normalized.FetchPolicy
import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.api.ConnectionMetadataGenerator
import com.apollographql.cache.normalized.api.ConnectionRecordMerger
import com.apollographql.cache.normalized.fetchPolicy
import com.apollographql.cache.normalized.memory.MemoryCacheFactory
import com.apollographql.cache.normalized.normalizedCache
import com.apollographql.cache.normalized.sql.SqlNormalizedCacheFactory
import com.example.apollokotlinpaginationsample.Application
import com.example.apollokotlinpaginationsample.BuildConfig
import com.example.apollokotlinpaginationsample.graphql.RepositoryListQuery
import com.example.apollokotlinpaginationsample.graphql.cache.Cache
import com.example.apollokotlinpaginationsample.graphql.pagination.Pagination

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
            normalizedCacheFactory = memoryThenSqlCache,
            cacheKeyGenerator = com.apollographql.cache.normalized.api.TypePolicyCacheKeyGenerator(
                typePolicies = Cache.typePolicies,
                keyScope = CacheKey.Scope.SERVICE,
            ),
            metadataGenerator = ConnectionMetadataGenerator(Pagination.connectionTypes),
            recordMerger = ConnectionRecordMerger
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
