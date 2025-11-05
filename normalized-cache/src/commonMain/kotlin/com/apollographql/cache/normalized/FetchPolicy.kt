package com.apollographql.cache.normalized

import com.apollographql.apollo.ApolloCall

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
   *
   * Warning: this can emit multiple successful responses, therefore [ApolloCall.execute] should not be used with this fetch policy.
   * Use only with [ApolloCall.toFlow] or [ApolloCall.watch].
   */
  CacheAndNetwork,
}
