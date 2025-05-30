plugins {
  id("org.jetbrains.kotlin.multiplatform")
}

group = "com.apollographql.cache"

kotlin {
  configureKmp(
      withJs = setOf(JsAndWasmEnvironment.Node),
      withWasm = setOf(JsAndWasmEnvironment.Node),
      withAndroid = false,
      withApple = AppleTargets.All,
  )

  sourceSets {
    getByName("commonMain") {
      dependencies {
        api(libs.kotlinx.coroutines.test)
        api(project(":normalized-cache"))
        implementation(libs.kotlin.test)
      }
    }
    getByName("jsMain") {
      dependencies {
        api(libs.kotlin.stdlib.js)
      }
    }

    getByName("wasmJsMain") {
      dependencies {
        api(libs.kotlin.stdlib.wasm.js)
      }
    }
  }
}
