import com.apollographql.apollo.annotations.ApolloExperimental
import java.io.File

fun prop(key: String) = project.findProperty(key).toString()

plugins {
  kotlin("multiplatform")
  id("org.jetbrains.compose")
  kotlin("plugin.compose")
  id("com.apollographql.apollo")
}

// Generate a BuildConfig.kt file with a constant for the GitHub OAuth key.
val generateBuildConfigTask = tasks.register("generateBuildConfig") {
  val outputDir = layout.buildDirectory.dir("generated/source/kotlin").get().asFile
  outputs.dir(outputDir)
  doFirst {
    val outputWithPackageDir = File(outputDir, "com/example/browsersample").apply { mkdirs() }
    File(outputWithPackageDir, "BuildConfig.kt").writeText(
        """
        package com.example.browsersample
        object BuildConfig {
          const val GITHUB_OAUTH_KEY = "${prop("githubOauthKey")}"
        }
      """.trimIndent()
    )
  }
}

kotlin {
  js {
    browser()
    binaries.executable()
    compilerOptions {
      target.set("es2015")
    }
  }

  sourceSets {
    jsMain {
      kotlin.srcDir(generateBuildConfigTask)

      dependencies {
        // Coroutines
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

        // Compose
        implementation("org.jetbrains.compose.runtime:runtime:1.8.0")
        implementation("org.jetbrains.compose.html:html-core:1.8.0")

        // Apollo
        implementation("com.apollographql.apollo:apollo-runtime")
        implementation("com.apollographql.cache:normalized-cache-sqlite:1.0.0-alpha.4-SNAPSHOT")

        // sqlite.js / SQLDelight
        implementation("app.cash.sqldelight:web-worker-driver:2.1.0")
        implementation(devNpm("copy-webpack-plugin", "9.1.0"))
        implementation(npm("sql.js", "1.8.0"))

        // Commented out as we use our own custom Worker that loads/saves the db file via OPFS.
        // See `src/jsMain/resources/sqljs.opfs.worker.js`
        // Uncomment to use the default SQLDelight worker instead, which stays in memory.
        // implementation(npm("@cashapp/sqldelight-sqljs-worker", "2.1.0"))
      }
    }
  }
}

apollo {
  service("main") {
    packageName.set("com.example.browsersample.graphql")

    @OptIn(ApolloExperimental::class)
    plugin("com.apollographql.cache:normalized-cache-apollo-compiler-plugin:1.0.0-alpha.4-SNAPSHOT") {
      argument("packageName", packageName.get())
    }

    introspection {
      endpointUrl.set("https://api.github.com/graphql")
      schemaFile.set(file("src/main/graphql/schema.graphqls"))
      headers.put("Authorization", "Bearer ${prop("githubOauthKey")}")
    }
  }
}

// `./gradlew jsBrowserDevelopmentRun --continuous` to run the dev server in continuous mode (should open `http://localhost:8080/`)
// `./gradlew jsBrowserDevelopmentExecutableDistribution` to build the dev distribution, results are in `build/dist/js/developmentExecutable`
// `./gradlew jsBrowserDistribution` to build the release distribution, results are in `build/dist/js/productionExecutable`
