@file:OptIn(ExperimentalPagingApi::class)

package com.example.apollokotlinpaginationsample.repository

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Optional
import com.apollographql.apollo.exception.ApolloGraphQLException
import com.apollographql.cache.normalized.FetchPolicy
import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.api.ConnectionMetadataGenerator
import com.apollographql.cache.normalized.api.ConnectionRecordMerger
import com.apollographql.cache.normalized.api.TypePolicyCacheKeyGenerator
import com.apollographql.cache.normalized.fetchPolicy
import com.apollographql.cache.normalized.memory.MemoryCacheFactory
import com.apollographql.cache.normalized.normalizedCache
import com.apollographql.cache.normalized.sql.SqlNormalizedCacheFactory
import com.apollographql.cache.normalized.watch
import com.example.apollokotlinpaginationsample.Application
import com.example.apollokotlinpaginationsample.BuildConfig
import com.example.apollokotlinpaginationsample.graphql.RepositoryListQuery
import com.example.apollokotlinpaginationsample.graphql.cache.Cache
import com.example.apollokotlinpaginationsample.graphql.cache.Cache.cache
import com.example.apollokotlinpaginationsample.graphql.pagination.Pagination
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch

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
        .cache(
            normalizedCacheFactory = memoryThenSqlCache,
            keyScope = CacheKey.Scope.SERVICE,
        )

        .build()
}

/**
 * Fetches pages of repositories and stores them to the normalized cache.
 *
 * This is called when the PagingSource can't find the requested pages in the cache, and when a refresh is requested.
 */
class RepositoryRemoteMediator : RemoteMediator<String, RepositoryListQuery.Edge>() {
    override suspend fun load(
        loadType: LoadType,
        state: PagingState<String, RepositoryListQuery.Edge>,
    ): MediatorResult {
        val lastItemCursor: String? = when (loadType) {
            LoadType.REFRESH -> {
                // Passing after=null fetches the first page
                null
            }

            LoadType.PREPEND -> {
                // Prepend is not supported
                return MediatorResult.Success(endOfPaginationReached = true)
            }

            LoadType.APPEND -> {
                val lastItem: RepositoryListQuery.Edge = state.lastItemOrNull()
                    ?: // This will be null the first time, when the cache is empty
                    return MediatorResult.Success(endOfPaginationReached = false)
                lastItem.cursor
            }
        }

        val loadSize =
            if (loadType == LoadType.REFRESH) state.config.initialLoadSize else state.config.pageSize
        val response = apolloClient.query(
            RepositoryListQuery(
                after = Optional.presentIfNotNull(lastItemCursor),
                first = Optional.present(loadSize),
            )
        )
            .fetchPolicy(FetchPolicy.NetworkOnly)
            .execute()
        if (response.data != null) {
            return MediatorResult.Success(endOfPaginationReached = response.data!!.organization!!.repositories.edges!!.size < loadSize)
        }
        return MediatorResult.Error(
            response.exception ?: ApolloGraphQLException(response.errors!!.first())
        )
    }
}

/**
 * Returns pages of repositories from the normalized cache.
 *
 * This implementation caches the entire list of repositories in memory to avoid accessing the cache for each page,
 * which optimizes for I/O at the expense of memory usage.
 */
class RepositoryPagingSource(
    private val coroutineScope: CoroutineScope,
) : PagingSource<String, RepositoryListQuery.Edge>() {
    private var allItems: List<RepositoryListQuery.Edge>? = null

    private suspend fun allItems(params: LoadParams<String>): List<RepositoryListQuery.Edge> {
        if (allItems == null || params is LoadParams.Refresh) {
            allItems = apolloClient.query(RepositoryListQuery())
                .fetchPolicy(FetchPolicy.CacheOnly)
                .execute()
                .data
                // Data will be null the first time (empty cache): treat it as an empty list
                ?.organization?.repositories?.edges.orEmpty().filterNotNull()
        }
        return allItems!!
    }

    override suspend fun load(params: LoadParams<String>): LoadResult<String, RepositoryListQuery.Edge> {
        // Get all items from the cache, and slice them according to the params
        val allItems = allItems(params)
        val indexOfCursor = allItems.indexOfFirst { it.cursor == params.key }
        val slice = if (indexOfCursor == -1) {
            allItems.take(params.loadSize)
        } else {
            when (params) {
                is LoadParams.Refresh, is LoadParams.Append -> {
                    allItems.drop(indexOfCursor + 1).take(params.loadSize)
                }

                is LoadParams.Prepend -> {
                    allItems.take(indexOfCursor).takeLast(params.loadSize)
                }
            }
        }

        // Watch the query to know when to invalidate this source
        coroutineScope.launch {
            apolloClient.query(RepositoryListQuery())
                .fetchPolicy(FetchPolicy.CacheOnly)
                .watch(null)
                .take(1)
                .collect {
                    invalidate()
                }
        }

        val prevKey = slice.firstOrNull()?.cursor
        val nextKey = slice.lastOrNull()?.cursor
        return LoadResult.Page(
            data = slice,
            prevKey = prevKey,
            nextKey = nextKey,
        )
    }

    override fun getRefreshKey(state: PagingState<String, RepositoryListQuery.Edge>): String? {
        return state.anchorPosition?.let {
            state.closestItemToPosition(
                (it - state.config.initialLoadSize / 2).coerceAtLeast(
                    0
                )
            )
        }?.cursor
    }

    override val keyReuseSupported = true
}
