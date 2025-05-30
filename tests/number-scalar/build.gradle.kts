plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.apollo)
}

kotlin {
  configureKmp(
      withJs = setOf(JsAndWasmEnvironment.Node, JsAndWasmEnvironment.Browser),
      withWasm = setOf(JsAndWasmEnvironment.Node, JsAndWasmEnvironment.Browser),
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
        implementation(libs.apollo.testing.support)
        implementation("com.apollographql.cache:test-utils")
        implementation(libs.kotlin.test)
      }
    }
  }
}

apollo {
  service("service") {
    packageName.set("com.example")
    mapScalar("Number", "kotlin.String")
  }
}
