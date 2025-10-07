package com.apollographql.cache.normalized.api

import kotlin.jvm.JvmInline

@JvmInline
value class EmbeddedFields(
    val embeddedFields: Set<String>,
)
