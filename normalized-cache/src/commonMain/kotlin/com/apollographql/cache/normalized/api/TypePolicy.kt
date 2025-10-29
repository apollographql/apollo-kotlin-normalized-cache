package com.apollographql.cache.normalized.api

import kotlin.jvm.JvmInline

@JvmInline
value class TypePolicy(
    val keyFields: List<String>,
)
