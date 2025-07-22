package com.apollographql.cache.normalized

interface CacheOptions {
  fun noCache(noCache: Boolean)
  fun onlyIfCached(onlyIfCached: Boolean)
  fun reload(reload: Boolean)
  fun allowCachedPartialResults(allowCachedPartialResults: Boolean)
  fun allowCachedErrors(allowCachedErrors: Boolean)
}

internal data class CacheOptionsImpl(
    var noCache: Boolean = false,
    var onlyIfCached: Boolean = false,
    var reload: Boolean = false,
    var allowCachedPartialResults: Boolean = false,
    var allowCachedErrors: Boolean = false,
) : CacheOptions {
  override fun noCache(noCache: Boolean) {
    if (noCache && onlyIfCached) error("Apollo: noCache and onlyIfCached are mutually exclusive")
    this.noCache = noCache
  }

  override fun onlyIfCached(onlyIfCached: Boolean) {
    if (onlyIfCached && noCache) error("Apollo: noCache and onlyIfCached are mutually exclusive")
    if (onlyIfCached && reload) error("Apollo: onlyIfCached and reload are mutually exclusive")
    this.onlyIfCached = onlyIfCached
  }

  override fun reload(reload: Boolean) {
    if (reload && onlyIfCached) error("Apollo: onlyIfCached and reload are mutually exclusive")
    this.reload = reload
  }

  override fun allowCachedPartialResults(allowCachedPartialResults: Boolean) {
    this.allowCachedPartialResults = allowCachedPartialResults
  }

  override fun allowCachedErrors(allowCachedErrors: Boolean) {
    this.allowCachedErrors = allowCachedErrors
  }
}
