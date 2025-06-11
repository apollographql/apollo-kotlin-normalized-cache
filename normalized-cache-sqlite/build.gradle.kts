import com.gradleup.librarian.gradle.Librarian

plugins {
  id("org.jetbrains.kotlin.multiplatform")
  id("com.android.library")
  id("app.cash.sqldelight")
}

Librarian.module(project)

kotlin {
  configureKmp(
      withJs = setOf(JsAndWasmEnvironment.Browser),
      withWasm = setOf(JsAndWasmEnvironment.Browser),
      withAndroid = true,
      withApple = AppleTargets.All,
  )
}

android {
  namespace = "com.apollographql.apollo.cache.normalized.sql"
  compileSdk = 34

  defaultConfig {
    minSdk = 16
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    multiDexEnabled = true
  }

  testOptions.targetSdk = 30
}

sqldelight {
  databases.create("SqlRecordDatabase") {
    packageName.set("com.apollographql.cache.normalized.sql.internal.record")
    schemaOutputDirectory.set(file("sqldelight/record/schema"))
    srcDirs.setFrom("src/commonMain/sqldelight/record/")
    generateAsync.set(true)
  }
}

kotlin {
  androidTarget {
    publishAllLibraryVariants()
  }

  sourceSets {
    getByName("commonMain") {
      dependencies {
        api(libs.apollo.api)
        api(project(":normalized-cache"))
        api(libs.sqldelight.runtime)
      }
    }

    getByName("jvmMain") {
      dependencies {
        implementation(libs.sqldelight.jvm)
      }
    }

    getByName("appleMain") {
      dependencies {
        implementation(libs.sqldelight.native)
      }
    }

    getByName("jvmTest") {
      dependencies {
        implementation(libs.truth)
        implementation(libs.slf4j.nop)
      }
    }

    getByName("androidMain") {
      dependencies {
        api(libs.androidx.sqlite)
        implementation(libs.sqldelight.android)
        implementation(libs.androidx.sqlite.framework)
        implementation(libs.androidx.startup.runtime)
      }
    }


    getByName("androidUnitTest") {
      dependencies {
        implementation(libs.kotlin.test.junit)
      }
    }

    getByName("commonTest") {
      dependencies {
        implementation(libs.kotlin.test)
        implementation(project(":test-utils"))
      }
    }

    getByName("jsCommonMain") {
      dependencies {
        implementation(libs.sqldelight.webworker)
        implementation(devNpm("copy-webpack-plugin", libs.versions.copyWebpackPlugin.get()))
      }
    }

    getByName("jsCommonTest") {
      dependencies {
        implementation(npm("@cashapp/sqldelight-sqljs-worker", libs.versions.sqldelight.get()))
        implementation(npm("sql.js", libs.versions.sqlJs.get()))
      }
    }
  }
}

tasks.configureEach {
  if (name.endsWith("UnitTest")) {
    /**
     * Because there is no App Startup in Android unit tests, the Android tests
     * fail at runtime so ignore them
     * We could make the Android unit tests use the Jdbc driver if we really wanted to
     */
    enabled = false
  }
}
