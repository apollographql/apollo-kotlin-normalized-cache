plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.apollo)
}

kotlin {
  configureKmp(
      withJs = setOf(JsAndWasmEnvironment.Node),
      withWasm = emptySet(),
      withAndroid = false,
      withApple = AppleTargets.Host,
  )

  sourceSets {
    getByName("commonMain") {
      dependencies {
        implementation(libs.apollo.runtime)
        implementation("com.apollographql.cache:normalized-cache")
      }
    }

    getByName("commonTest") {
      dependencies {
        implementation(libs.kotlin.test)
        implementation("com.apollographql.cache:test-utils")
        implementation(libs.apollo.mockserver)
        implementation(libs.turbine)
      }
    }
  }
}

apollo {
  service("service") {
    packageName.set("optimistic")
  }
}
