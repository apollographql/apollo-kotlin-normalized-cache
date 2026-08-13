package com.apollographql.cache.normalized.testing

import com.apollographql.cache.normalized.api.ApolloCacheHeaders
import com.apollographql.cache.normalized.api.CacheHeaders

val noExceptionsHeaders = CacheHeaders.Builder()
    .addHeader(ApolloCacheHeaders.CACHE_MISSES_AS_EXCEPTION, "false")
    .addHeader(ApolloCacheHeaders.SERVER_ERRORS_AS_EXCEPTION, "false")
    .build()
