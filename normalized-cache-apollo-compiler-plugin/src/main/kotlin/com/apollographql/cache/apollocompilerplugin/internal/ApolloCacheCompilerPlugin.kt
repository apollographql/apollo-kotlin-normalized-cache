@file:OptIn(ApolloExperimental::class)

package com.apollographql.cache.apollocompilerplugin.internal

import com.apollographql.apollo.annotations.ApolloExperimental
import com.apollographql.apollo.ast.ForeignSchema
import com.apollographql.apollo.compiler.ApolloCompilerPlugin
import com.apollographql.apollo.compiler.ApolloCompilerPluginEnvironment
import com.apollographql.apollo.compiler.DocumentTransform
import com.apollographql.apollo.compiler.SchemaListener

internal class ApolloCacheCompilerPlugin(
    private val environment: ApolloCompilerPluginEnvironment,
) : ApolloCompilerPlugin {
  override fun foreignSchemas(): List<ForeignSchema> {
    return listOf(ForeignSchema("cache", "v0.1", cacheControlGQLDefinitions))
  }

  override fun schemaListener(): SchemaListener {
    return CacheSchemaListener(environment)
  }

  override fun documentTransform(): DocumentTransform {
    return AddKeyFieldsDocumentTransform()
  }
}
