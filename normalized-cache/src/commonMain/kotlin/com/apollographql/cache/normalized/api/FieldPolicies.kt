package com.apollographql.cache.normalized.api

import kotlin.jvm.JvmInline

@JvmInline
value class FieldPolicies(
    val fieldPolicies: Map<String, FieldPolicy>,
) {
  @JvmInline
  value class FieldPolicy(
      val keyArgs: List<String>,
  )
}
