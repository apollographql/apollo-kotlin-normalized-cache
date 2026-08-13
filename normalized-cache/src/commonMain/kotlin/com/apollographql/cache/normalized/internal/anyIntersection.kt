package com.apollographql.cache.normalized.internal

/**
 * Returns whether this set and [other] have at least one element in common.
 *
 * Unlike `intersect`, this allocates nothing and stops at the first match. Elements are looked up in
 * the larger of the two sets, so the number of (constant time) lookups is bounded by the size of the
 * smaller one.
 */
internal fun <T> Set<T>.anyIntersection(other: Set<T>): Boolean {
  return if (size < other.size) {
    any { it in other }
  } else {
    other.any { it in this }
  }
}
