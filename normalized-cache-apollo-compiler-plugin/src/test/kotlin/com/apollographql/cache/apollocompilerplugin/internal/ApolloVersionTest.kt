package com.apollographql.cache.apollocompilerplugin.internal

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.gradle.testkit.runner.UnexpectedBuildFailure
import java.io.File
import kotlin.test.Test
import kotlin.test.Ignore
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Those tests all share the same `build/testProject` directory and are therefore synchronized.
 */
class ApolloVersionTest {

  @Test
  @Synchronized
  fun apollo_4_2_fails() {
    withProject {
      it.version("4.2.0")
    }.assertFailure("generateServiceApolloSources") {
      assertTrue(it.message!!.contains("The Apollo Cache compiler plugin requires Apollo Kotlin version 4.3.0 or higher (found 4.2.0)"))
    }
  }

  @Test
  @Synchronized
  fun apollo_4_3_succeeds() {
    withProject {
      it.version("4.3.0")
    }.assertSuccess("build")
  }

  @Test
  @Synchronized
  @Ignore("There is no 5.0.0 release yet")
  fun apollo_5_0_succeeds() {
    withProject {
      it.version("5.0.0-SNAPSHOT")
    }.assertSuccess("build")
  }

  @Test
  @Synchronized
  fun apollo_4_3_shows_error_message() {
    withProject {
      it.version("4.3.0")
      it.addBadCacheControl()
    }.assertFailure("generateServiceApolloSources") {
      assertTrue(it.message!!.contains("`maxAge` must not be negative"))
    }
  }

  @Test
  @Synchronized
  @Ignore("There is no 5.0.0 release yet")
  fun apollo_5_0_shows_error_message() {
    withProject {
      it.version("5.0.0-SNAPSHOT")
      it.addBadCacheControl()
    }.assertFailure("generateServiceApolloSources") {
      assertTrue(it.message!!.contains("`maxAge` must not be negative"))
    }
  }
}

private fun withProject(block: (File) -> Unit): File {
  val workingDir = File("build/testProject")

  workingDir.deleteRecursively()
  File("test-data/testProject").copyRecursively(workingDir)
  block(workingDir)
  return workingDir
}

private fun File.addBadCacheControl() {
  resolve("src/main/graphql/schema.graphqls").replace("# DIRECTIVE_PLACEHOLDER", "@cacheControl(maxAge: -10)")
}

private fun File.version(version: String) {
  resolve("build.gradle.kts").replace("4.2.0", version)
}
private fun File.assertFailure(taskName: String, onFailure: (UnexpectedBuildFailure) -> Unit) {
  newRunner().assertFailure(taskName, onFailure)
}

private fun File.assertSuccess(taskName: String) {
  newRunner().assertSuccess(taskName)
}

private fun File.newRunner(): GradleRunner {
  return GradleRunner.create()
    .withProjectDir(this)
}

private fun GradleRunner.assertFailure(taskName: String, onFailure: (UnexpectedBuildFailure) -> Unit) {
  try {
    withArguments(taskName)
      .build()
  } catch (e: UnexpectedBuildFailure) {
    onFailure(e)
  }
}

private fun GradleRunner.assertSuccess(taskName: String) {
  withArguments(taskName)
    .build().apply {
      assertEquals(TaskOutcome.SUCCESS, task(":$taskName")!!.outcome)
    }
}

private fun File.replace(str: String, replacement: String) {
  writeText(readText().replace(str, replacement))
}