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
        implementation("com.apollographql.cache:test-utils")
        implementation(libs.apollo.mockserver)
        implementation(libs.kotlin.test)
      }
    }
  }
}

apollo {
  service("fixtures") {
    srcDir(file("../models-fixtures/graphql"))
    packageName.set("codegen.models")
    generateFragmentImplementations.set(true)
  }
}
