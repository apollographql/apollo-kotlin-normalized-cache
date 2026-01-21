plugins {
  id("org.jetbrains.kotlin.multiplatform")
  id("com.android.library")
}

lib()

kotlin {
  configureKmp(
      withJs = emptySet(),
      withWasm = emptySet(),
      withAndroid = true,
      withApple = AppleTargets.All,
  )
}

android {
  namespace = "com.apollographql.apollo.cache.normalized.rocksdb"
  compileSdk = 34

  defaultConfig {
    minSdk = 16
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    multiDexEnabled = true
  }

  testOptions.targetSdk = 30
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
        implementation("io.maryk.rocksdb:rocksdb-multiplatform:10.4.6")
      }
    }

    getByName("jvmMain") {
      dependencies {
      }
    }

    getByName("appleMain") {
      dependencies {
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
