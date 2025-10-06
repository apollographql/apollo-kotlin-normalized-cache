package com.apollographql.cache.apollocompilerplugin.internal

import com.apollographql.apollo.annotations.ApolloExperimental
import com.apollographql.apollo.ast.SourceLocation
import com.apollographql.apollo.ast.pretty
import com.apollographql.apollo.compiler.ApolloCompiler
import java.lang.reflect.Method

private val errorMethod: Method? by lazy {
  ApolloCompiler.Logger::class.java.methods.singleOrNull { it.name == "error" }
}

internal class IssueLogger(private val logger: ApolloCompiler.Logger) {
  var hasIssues = false

  @OptIn(ApolloExperimental::class)
  fun logIssue(message: String, sourceLocation: SourceLocation?) {
    hasIssues = true
    val str = "${sourceLocation.pretty()}: $message"
    if (errorMethod != null) {
      // v5+ we have an `error()` method, but it doesn't add the "e: " prefix
      errorMethod!!.invoke(logger, "e: [apollo] $str")
    } else {
      // v4- ApolloCompiler.Logger.warning() redirects to ApolloCompilerPluginLogger.error()
      logger.warning(str)
    }
  }
}

