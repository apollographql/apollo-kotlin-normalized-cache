@file:Suppress("PackageDirectoryMismatch")

package com.apollographql.cache.normalized

import com.apollographql.apollo.api.ApolloResponse
import com.apollographql.apollo.api.ExecutionContext
import com.apollographql.apollo.api.Operation
import com.apollographql.apollo.exception.ApolloException
import com.apollographql.apollo.exception.CacheMissException

class CacheInfo private constructor(
    val cacheStartMillis: Long,
    val cacheEndMillis: Long,
    val networkStartMillis: Long,
    val networkEndMillis: Long,

    /**
     * True if the response is from the cache, false if it's from the network.
     */
    val isFromCache: Boolean,

    /**
     * True if **all** the fields are found in the cache, false for full or partial cache misses.
     */
    val isCacheHit: Boolean,

    /**
     * The exception that occurred while reading the cache.
     */
    val cacheMissException: CacheMissException?,

    /**
     * The exception that occurred while reading the network.
     */
    val networkException: ApolloException?,

    /**
     * True if at least one field in the response is stale.
     * Always `false` if [isFromCache] is false.
     */
    val isStale: Boolean,
) : ExecutionContext.Element {
  override val key: ExecutionContext.Key<*>
    get() = Key

  companion object Key : ExecutionContext.Key<CacheInfo>

  fun newBuilder(): Builder {
    return Builder().cacheStartMillis(cacheStartMillis)
        .cacheEndMillis(cacheEndMillis)
        .networkStartMillis(networkStartMillis)
        .networkEndMillis(networkEndMillis)
        .fromCache(isFromCache)
        .cacheHit(isCacheHit)
        .cacheMissException(cacheMissException)
        .networkException(networkException)
        .stale(isStale)
  }

  class Builder {
    private var cacheStartMillis: Long = 0
    private var cacheEndMillis: Long = 0
    private var networkStartMillis: Long = 0
    private var networkEndMillis: Long = 0
    private var fromCache: Boolean = false
    private var cacheHit: Boolean = false
    private var cacheMissException: CacheMissException? = null
    private var networkException: ApolloException? = null
    private var stale: Boolean = false

    fun cacheStartMillis(cacheStartMillis: Long) = apply {
      this.cacheStartMillis = cacheStartMillis
    }

    fun cacheEndMillis(cacheEndMillis: Long) = apply {
      this.cacheEndMillis = cacheEndMillis
    }

    fun networkStartMillis(networkStartMillis: Long) = apply {
      this.networkStartMillis = networkStartMillis
    }

    fun networkEndMillis(networkEndMillis: Long) = apply {
      this.networkEndMillis = networkEndMillis
    }

    fun fromCache(fromCache: Boolean) = apply {
      this.fromCache = fromCache
    }

    fun cacheHit(cacheHit: Boolean) = apply {
      this.cacheHit = cacheHit
    }

    fun cacheMissException(cacheMissException: CacheMissException?) = apply {
      this.cacheMissException = cacheMissException
    }

    fun networkException(networkException: ApolloException?) = apply {
      this.networkException = networkException
    }

    fun stale(stale: Boolean) = apply {
      this.stale = stale
    }

    fun build(): CacheInfo = CacheInfo(
        cacheStartMillis = cacheStartMillis,
        cacheEndMillis = cacheEndMillis,
        networkStartMillis = networkStartMillis,
        networkEndMillis = networkEndMillis,
        isFromCache = fromCache,
        isCacheHit = cacheHit,
        cacheMissException = cacheMissException,
        networkException = networkException,
        isStale = stale,
    )
  }
}

/**
 * True if this response comes from the cache, false if it comes from the network.
 *
 * Note that this can be true regardless of whether the data was found in the cache.
 * To know whether the **data** is from the cache, use `cacheInfo?.isCacheHit == true`.
 */
val <D : Operation.Data> ApolloResponse<D>.isFromCache: Boolean
  get() {
    return cacheInfo?.isFromCache == true
  }

val <D : Operation.Data> ApolloResponse<D>.cacheInfo
  get() = executionContext[CacheInfo]

internal fun <D : Operation.Data> ApolloResponse<D>.withCacheInfo(cacheInfo: CacheInfo) =
  newBuilder().addExecutionContext(cacheInfo).build()

internal fun <D : Operation.Data> ApolloResponse.Builder<D>.cacheInfo(cacheInfo: CacheInfo) = addExecutionContext(cacheInfo)

