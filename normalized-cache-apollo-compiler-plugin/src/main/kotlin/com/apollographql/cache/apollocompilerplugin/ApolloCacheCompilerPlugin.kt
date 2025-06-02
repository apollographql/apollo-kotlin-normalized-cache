@file:OptIn(ApolloExperimental::class)

package com.apollographql.cache.apollocompilerplugin

import com.apollographql.apollo.annotations.ApolloExperimental
import com.apollographql.apollo.ast.ForeignSchema
import com.apollographql.apollo.compiler.ApolloCompilerPlugin
import com.apollographql.apollo.compiler.ApolloCompilerPluginEnvironment
import com.apollographql.apollo.compiler.ApolloCompilerRegistry
import com.apollographql.cache.apollocompilerplugin.internal.AddKeyFieldsExecutableDocumentTransform
import com.apollographql.cache.apollocompilerplugin.internal.CacheCodeGenerator
import com.apollographql.cache.apollocompilerplugin.internal.cacheGQLDefinitions

class ApolloCacheCompilerPlugin : ApolloCompilerPlugin {
  override fun beforeCompilationStep(
      environment: ApolloCompilerPluginEnvironment,
      registry: ApolloCompilerRegistry,
  ) {
    registry.registerForeignSchemas(listOf(ForeignSchema("cache", "v0.1", cacheGQLDefinitions)))
    registry.registerExecutableDocumentTransform("com.apollographql.cache.addKeyFields", transform = AddKeyFieldsExecutableDocumentTransform)
    registry.registerExtraCodeGenerator(CacheCodeGenerator(environment))
  }
}
