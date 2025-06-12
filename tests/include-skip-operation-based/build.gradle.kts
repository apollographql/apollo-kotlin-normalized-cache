import com.apollographql.apollo.annotations.ApolloExperimental

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
      }
    }
  }
}

apollo {
  service("service") {
    packageName.set("com.example")
    @OptIn(ApolloExperimental::class)
    generateDataBuilders.set(true)
  }
}
