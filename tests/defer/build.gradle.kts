plugins {
  alias(libs.plugins.kotlin.multiplatform)
  id("com.apollographql.apollo")
}

kotlin {
  configureKmp(
      withJs = setOf(JsAndWasmEnvironment.Node),
      withWasm = emptySet(),
      withAndroid = false,
      withApple = AppleTargets.Host,
  )

  sourceSets {
    findByName("commonMain")?.apply {
      dependencies {
        implementation(libs.apollo.runtime)
        implementation("com.apollographql.cache:normalized-cache")
      }
    }

    findByName("commonTest")?.apply {
      dependencies {
        implementation(libs.kotlin.test)
        implementation("com.apollographql.cache:test-utils")
        implementation(libs.apollo.mockserver)
      }
    }
  }
}

apollo {
  service("base") {
    packageName.set("defer")
    generateFragmentImplementations.set(true)
  }
}
