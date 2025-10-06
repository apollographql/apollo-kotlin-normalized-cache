package com.apollographql.cache.apollocompilerplugin.internal

import com.apollographql.apollo.annotations.ApolloExperimental
import com.apollographql.apollo.ast.GQLBooleanValue
import com.apollographql.apollo.ast.GQLDirective
import com.apollographql.apollo.ast.GQLIntValue
import com.apollographql.apollo.ast.GQLStringValue
import com.apollographql.apollo.ast.Schema
import com.apollographql.apollo.compiler.ApolloCompiler

@OptIn(ApolloExperimental::class)
internal fun Schema.getMaxAges(logger: ApolloCompiler.Logger): Map<String, Int> {
  val issueLogger = IssueLogger(logger)
  val typeDefinitions = this.typeDefinitions
  fun GQLDirective.maxAgeAndInherit(): Pair<Int?, Boolean> {
    val maxAge = (arguments.firstOrNull { it.name == "maxAge" }?.value as? GQLIntValue)?.value?.toIntOrNull()
    if (maxAge != null && maxAge < 0) {
      issueLogger.logIssue("`maxAge` must not be negative", sourceLocation)
      return null to false
    }
    val inheritMaxAge = (arguments.firstOrNull { it.name == "inheritMaxAge" }?.value as? GQLBooleanValue)?.value == true
    if (maxAge == null && !inheritMaxAge || maxAge != null && inheritMaxAge) {
      issueLogger.logIssue("`@$name` must either provide a `maxAge` or an `inheritMaxAge` set to true", sourceLocation)
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
        issueLogger.logIssue("Field `$fieldName` does not exist on type `${typeDefinition.name}`", fieldDirective.sourceLocation)
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
  if (issueLogger.hasIssues) {
    throw IllegalStateException("Issues found while parsing `@cacheControl`/`@cacheControlField` directives (see logs above)")
  }
  return maxAges
}

private const val CACHE_CONTROL = "cacheControl"
private const val CACHE_CONTROL_FIELD = "cacheControlField"

