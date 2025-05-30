plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.apollo)
}

kotlin {
  configureKmp(
      withJs = emptySet(),
      withWasm = emptySet(),
      withAndroid = false,
      withApple = AppleTargets.Host,
  )

  sourceSets {
    getByName("commonMain") {
      dependencies {
        implementation(libs.apollo.runtime)
        implementation(libs.apollo.cache)
        implementation(libs.apollo.cache.sqlite)
        implementation("com.apollographql.cache:normalized-cache-sqlite")
      }
    }

    getByName("commonTest") {
      dependencies {
        implementation("com.apollographql.cache:test-utils")
        implementation(libs.apollo.mockserver)
        implementation(libs.kotlin.test)
      }
    }
  }
}

apollo {
  service("service") {
    packageName.set("test")
  }
}
