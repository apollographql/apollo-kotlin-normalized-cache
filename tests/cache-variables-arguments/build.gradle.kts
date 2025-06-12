plugins {
  alias(libs.plugins.kotlin.multiplatform)
  id("com.apollographql.apollo")
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
        implementation(libs.kotlin.test)
        implementation("com.apollographql.cache:test-utils")
      }
    }
  }
}

apollo {
  service("service") {
    packageName.set("cache.include")
  }
}
