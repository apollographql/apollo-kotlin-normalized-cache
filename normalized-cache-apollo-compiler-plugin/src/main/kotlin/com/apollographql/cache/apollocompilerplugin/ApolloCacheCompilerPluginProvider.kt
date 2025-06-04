@file:OptIn(ApolloExperimental::class)

package com.apollographql.cache.apollocompilerplugin

import com.apollographql.apollo.annotations.ApolloExperimental
import com.apollographql.apollo.compiler.APOLLO_VERSION
import com.apollographql.apollo.compiler.ApolloCompilerPlugin
import com.apollographql.apollo.compiler.ApolloCompilerPluginEnvironment
import com.apollographql.cache.apollocompilerplugin.internal.ApolloCacheCompilerPlugin

class ApolloCacheCompilerPluginProvider : @Suppress("DEPRECATION") com.apollographql.apollo.compiler.ApolloCompilerPluginProvider {
  override fun create(environment: ApolloCompilerPluginEnvironment): ApolloCompilerPlugin {
    checkCompilerVersion()
    return ApolloCacheCompilerPlugin()
  }
}

private fun checkCompilerVersion() {
  val matchResult = Regex("""^(\d+)\.(\d+).*$""").matchEntire(APOLLO_VERSION)
  val versionMajor = matchResult?.groupValues?.get(1)?.toIntOrNull()
  val versionMinor = matchResult?.groupValues?.get(2)?.toIntOrNull()
  if (versionMinor == null || versionMajor == null) error("Invalid Apollo Kotlin compiler version: $APOLLO_VERSION")
  if (versionMajor < 4 || (versionMajor == 4 && versionMinor < 3)) {
    error("The Apollo Cache compiler plugin requires Apollo Kotlin version 4.3.0 or higher (found $APOLLO_VERSION)")
  }
}
