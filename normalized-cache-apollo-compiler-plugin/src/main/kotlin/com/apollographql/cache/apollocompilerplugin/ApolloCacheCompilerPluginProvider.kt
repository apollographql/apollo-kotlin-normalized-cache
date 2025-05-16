@file:OptIn(ApolloExperimental::class)

package com.apollographql.cache.apollocompilerplugin

import com.apollographql.apollo.annotations.ApolloExperimental
import com.apollographql.apollo.compiler.ApolloCompilerPlugin
import com.apollographql.apollo.compiler.ApolloCompilerPluginEnvironment
import com.apollographql.apollo.compiler.ApolloCompilerPluginProvider
import com.apollographql.cache.apollocompilerplugin.internal.ApolloCacheCompilerPlugin

class ApolloCacheCompilerPluginProvider : ApolloCompilerPluginProvider {
  override fun create(environment: ApolloCompilerPluginEnvironment): ApolloCompilerPlugin {
    return ApolloCacheCompilerPlugin(environment)
  }
}
