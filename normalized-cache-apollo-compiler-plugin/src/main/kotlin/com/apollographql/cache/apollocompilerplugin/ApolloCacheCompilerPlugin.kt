@file:OptIn(ApolloExperimental::class)

package com.apollographql.cache.apollocompilerplugin

import com.apollographql.apollo.annotations.ApolloExperimental
import com.apollographql.apollo.compiler.ApolloCompilerPlugin
import com.apollographql.apollo.compiler.ApolloCompilerPluginEnvironment
import com.apollographql.apollo.compiler.ApolloCompilerRegistry
import com.apollographql.cache.apollocompilerplugin.internal.AddKeyFieldsExecutableDocumentTransform
import com.apollographql.cache.apollocompilerplugin.internal.CacheSchemaCodeGenerator
import com.apollographql.cache.apollocompilerplugin.internal.cacheForeignSchemas

class ApolloCacheCompilerPlugin : ApolloCompilerPlugin {
  override fun beforeCompilationStep(
      environment: ApolloCompilerPluginEnvironment,
      registry: ApolloCompilerRegistry,
  ) {
    registry.registerForeignSchemas(cacheForeignSchemas)
    registry.registerExecutableDocumentTransform("com.apollographql.cache.addKeyFields", transform = AddKeyFieldsExecutableDocumentTransform)
    registry.registerSchemaCodeGenerator(CacheSchemaCodeGenerator(environment))
  }
}
