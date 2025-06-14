plugins {
  id("org.jetbrains.kotlin.multiplatform")
  id("org.jetbrains.kotlin.plugin.atomicfu")
}

lib()

kotlin {
  configureKmp(
      withJs = setOf(JsAndWasmEnvironment.Node, JsAndWasmEnvironment.Browser),
      withWasm = setOf(JsAndWasmEnvironment.Node, JsAndWasmEnvironment.Browser),
      withAndroid = false,
      withApple = AppleTargets.All,
  )

  sourceSets {
    getByName("commonMain") {
      dependencies {
        api(libs.apollo.runtime)
        api(libs.apollo.mpp.utils)
        implementation(libs.okio)
        api(libs.uuid)
        implementation(libs.atomicfu.library)
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

    getByName("commonTest") {
      dependencies {
        implementation(libs.kotlin.test)
        implementation(project(":test-utils"))
      }
    }
  }
}
