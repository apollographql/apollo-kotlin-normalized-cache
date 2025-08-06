package com.apollographql.cache.normalized

enum class FetchPolicy {
  /**
   * Emit the response from the cache first, and if there was a cache miss, emit the response(s) from the network.
   *
   * This is the default behaviour.
   */
  CacheFirst,

  /**
   * Emit the response from the cache only.
   */
  CacheOnly,

  /**
   * Emit the response(s) from the network first, and if there was a network error, emit the response from the cache.
   */
  NetworkFirst,

  /**
   * Emit the response(s) from the network only.
   */
  NetworkOnly,

  /**
   * Emit the response from the cache first, and then emit the response(s) from the network.
   */
  @Deprecated("This is equivalent of executing with CacheOnly and then with NetworkOnly")
  CacheAndNetwork,
}
