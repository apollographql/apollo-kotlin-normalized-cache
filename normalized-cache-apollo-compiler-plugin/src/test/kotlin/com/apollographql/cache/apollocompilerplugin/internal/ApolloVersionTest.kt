package com.apollographql.cache.apollocompilerplugin.internal

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.UnexpectedBuildFailure
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ApolloVersionTest {

  @Test
  fun failsBelowApollo4_3() {
    val workingDir = File("build/testProject")

    workingDir.deleteRecursively()
    File("test-data/testProject").copyRecursively(workingDir)
    try {
      GradleRunner.create()
          .withProjectDir(workingDir)
          .withDebug(true)
          .withArguments("generateServiceApolloSources")
          .build()
    } catch (e: UnexpectedBuildFailure) {
      assertTrue(e.message!!.contains("The Apollo Cache compiler plugin requires Apollo Kotlin version 4.3.0 or higher (found 4.2.0)"))
    }
  }
}