package com.apollographql.cache.normalized

interface CacheOptions {
  fun noCache(noCache: Boolean)
  fun onlyIfCached(onlyIfCached: Boolean)
  fun allowCachedPartialResults(allowCachedPartialResults: Boolean)
  fun allowCachedErrors(allowCachedErrors: Boolean)
}

internal data class CacheOptionsImpl(
    var noCache: Boolean = false,
    var onlyIfCached: Boolean = false,
    var allowCachedPartialResults: Boolean = false,
    var allowCachedErrors: Boolean = false,
) : CacheOptions {
  override fun noCache(noCache: Boolean) {
    // noCache and onlyIfCached are mutually exclusive
    if (noCache) {
      onlyIfCached = false
    }
    this.noCache = noCache
  }

  override fun onlyIfCached(onlyIfCached: Boolean) {
    // noCache and onlyIfCached are mutually exclusive
    if (onlyIfCached) {
      noCache = false
    }
    this.onlyIfCached = onlyIfCached
  }

  override fun allowCachedPartialResults(allowCachedPartialResults: Boolean) {
    this.allowCachedPartialResults = allowCachedPartialResults
  }

  override fun allowCachedErrors(allowCachedErrors: Boolean) {
    this.allowCachedErrors = allowCachedErrors
  }
}
