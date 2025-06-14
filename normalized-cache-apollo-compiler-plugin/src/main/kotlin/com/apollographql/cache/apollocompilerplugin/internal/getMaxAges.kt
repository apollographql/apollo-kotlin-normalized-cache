package com.apollographql.cache.apollocompilerplugin.internal

import com.apollographql.apollo.annotations.ApolloExperimental
import com.apollographql.apollo.ast.GQLBooleanValue
import com.apollographql.apollo.ast.GQLDirective
import com.apollographql.apollo.ast.GQLIntValue
import com.apollographql.apollo.ast.GQLInterfaceTypeDefinition
import com.apollographql.apollo.ast.GQLObjectTypeDefinition
import com.apollographql.apollo.ast.GQLStringValue
import com.apollographql.apollo.ast.GQLTypeDefinition
import com.apollographql.apollo.ast.Schema
import com.apollographql.apollo.ast.SourceLocation
import com.apollographql.apollo.ast.pretty
import com.apollographql.apollo.compiler.ApolloCompiler
import com.apollographql.apollo.compiler.ApolloCompilerPluginLogger
import java.lang.reflect.Method

@OptIn(ApolloExperimental::class)
internal fun Schema.getMaxAges(logger: ApolloCompiler.Logger): Map<String, Int> {
  var hasIssues = false
  val typeDefinitions = this.typeDefinitions
  fun GQLDirective.maxAgeAndInherit(): Pair<Int?, Boolean> {
    val maxAge = (arguments.firstOrNull { it.name == "maxAge" }?.value as? GQLIntValue)?.value?.toIntOrNull()
    if (maxAge != null && maxAge < 0) {
      hasIssues = true
      logger.logIssue("`maxAge` must not be negative", sourceLocation)
      return null to false
    }
    val inheritMaxAge = (arguments.firstOrNull { it.name == "inheritMaxAge" }?.value as? GQLBooleanValue)?.value == true
    if (maxAge == null && !inheritMaxAge || maxAge != null && inheritMaxAge) {
      hasIssues = true
      logger.logIssue("`@$name` must either provide a `maxAge` or an `inheritMaxAge` set to true", sourceLocation)
      return null to false
    }
    return maxAge to inheritMaxAge
  }

  val maxAges = mutableMapOf<String, Int>()
  for (typeDefinition in typeDefinitions.values) {
    val typeCacheControlDirective = typeDefinition.directives.firstOrNull { this.originalDirectiveName(it.name) == CACHE_CONTROL }
    if (typeCacheControlDirective != null) {
      val (maxAge, inheritMaxAge) = typeCacheControlDirective.maxAgeAndInherit()
      if (maxAge != null) {
        maxAges[typeDefinition.name] = maxAge
      } else if (inheritMaxAge) {
        maxAges[typeDefinition.name] = -1
      }
    }

    val typeCacheControlFieldDirectives =
      typeDefinition.directives.filter { this.originalDirectiveName(it.name) == CACHE_CONTROL_FIELD }
    for (fieldDirective in typeCacheControlFieldDirectives) {
      val fieldName = (fieldDirective.arguments.first { it.name == "name" }.value as GQLStringValue).value
      if (typeDefinition.fields.none { it.name == fieldName }) {
        hasIssues = true
        logger.logIssue("Field `$fieldName` does not exist on type `${typeDefinition.name}`", fieldDirective.sourceLocation)
        continue
      }
      val (maxAge, inheritMaxAge) = fieldDirective.maxAgeAndInherit()
      if (maxAge != null) {
        maxAges["${typeDefinition.name}.$fieldName"] = maxAge
      } else if (inheritMaxAge) {
        maxAges["${typeDefinition.name}.$fieldName"] = -1
      }
    }

    for (field in typeDefinition.fields) {
      val fieldCacheControlDirective = field.directives.firstOrNull { this.originalDirectiveName(it.name) == CACHE_CONTROL }
      if (fieldCacheControlDirective != null) {
        val (maxAge, inheritMaxAge) = fieldCacheControlDirective.maxAgeAndInherit()
        if (maxAge != null) {
          maxAges["${typeDefinition.name}.${field.name}"] = maxAge
        } else if (inheritMaxAge) {
          maxAges["${typeDefinition.name}.${field.name}"] = -1
        }
      }
    }
  }
  if (hasIssues) {
    throw IllegalStateException("Issues found while parsing `@cacheControl`/`@cacheControlField` directives (see logs above)")
  }
  return maxAges
}

private const val CACHE_CONTROL = "cacheControl"
private const val CACHE_CONTROL_FIELD = "cacheControlField"

private val errorMethod: Method? by lazy {
  ApolloCompiler.Logger::class.java.methods.singleOrNull { it.name == "error" }
}

@OptIn(ApolloExperimental::class)
private fun ApolloCompiler.Logger.logIssue(message: String, sourceLocation: SourceLocation?) {
  val str = "${sourceLocation.pretty()}: ${message}"
  if (errorMethod != null) {
    // v5+ we have an `error()` method, but it doesn't add the "e: " prefix
    errorMethod!!.invoke(this, "e: [apollo] $str")
  } else {
    // v4- ApolloCompiler.Logger.warning() redirects to ApolloCompilerPluginLogger.error()
    warning(str)
  }
}

private val GQLTypeDefinition.fields
  get() = when (this) {
    is GQLObjectTypeDefinition -> fields
    is GQLInterfaceTypeDefinition -> fields
    else -> emptyList()
  }

