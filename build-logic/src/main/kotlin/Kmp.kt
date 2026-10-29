import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.TestExecutable
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest

enum class AppleTargets {
  All,
  Host,
  None,
}

enum class JsAndWasmEnvironment {
  Node,
  Browser,
}

fun KotlinMultiplatformExtension.configureKmp(
    withJs: Set<JsAndWasmEnvironment>,
    withWasm: Set<JsAndWasmEnvironment>,
    withAndroid: Boolean,
    withApple: AppleTargets,
) {
  jvm()
  when (withApple) {
    AppleTargets.All -> {
      macosX64()
      macosArm64()
      iosArm64()
      iosX64()
      iosSimulatorArm64()
      watchosArm32()
      watchosArm64()
      watchosDeviceArm64()
      watchosSimulatorArm64()
      tvosArm64()
      tvosX64()
      tvosSimulatorArm64()
    }

    AppleTargets.Host -> {
      if (System.getProperty("os.arch") == "aarch64") {
        macosArm64()
      } else {
        macosX64()
      }
    }

    AppleTargets.None -> {
      // No Apple targets
    }
  }
  if (withJs.isNotEmpty()) {
    js(IR) {
      if (withJs.contains(JsAndWasmEnvironment.Browser)) {
        browser {
          testTask {
            useKarma {
              useChromeHeadless()
            }
          }
        }
      }
      if (withJs.contains(JsAndWasmEnvironment.Node)) {
        nodejs {
          testTask {
            useMocha {
              // Override default 2s timeout
              timeout = "120s"
            }
          }
        }
      }
    }
  }
  if (withWasm.isNotEmpty()) {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
      if (withWasm.contains(JsAndWasmEnvironment.Browser)) {
        browser {
          testTask {
            useKarma {
              useChromeHeadless()
            }
          }
        }
      }
      if (withWasm.contains(JsAndWasmEnvironment.Node)) {
        // Mocha test framework for Wasm target is not supported
        // See https://youtrack.jetbrains.com/issue/KT-74612
        nodejs()
      }
    }
  }
  if (withAndroid) {
    androidTarget {
      publishLibraryVariants("release", "debug")
    }
  }

  // Only run native tests on macosArm64. Simulator tests are slow and flaky on CI.
  targets.withType(KotlinNativeTarget::class.java).configureEach {
    if (name != "macosArm64") {
      compilations.getByName("test").compileTaskProvider.configure {
        enabled = false
      }
      binaries.matching { it is TestExecutable }.configureEach {
        linkTaskProvider.configure {
          enabled = false
        }
      }
      val testTaskName = "${name}Test"
      project.tasks.withType(KotlinNativeTest::class.java).named { it == testTaskName }.configureEach {
        enabled = false
      }
    }
  }

  @OptIn(ExperimentalKotlinGradlePluginApi::class)
  applyDefaultHierarchyTemplate {
    group("common") {
      group("concurrent") {
        if (withApple != AppleTargets.None) {
          group("native") {
            group("apple")
          }
        }
        group("jvmCommon") {
          withJvm()
          if (withAndroid) {
            withAndroidTarget()
          }
        }
      }
      if (withJs.isNotEmpty() || withWasm.isNotEmpty()) {
        group("jsCommon") {
          if (withJs.isNotEmpty()) {
            group("js") {
              withJs()
            }
          }
          if (withWasm.isNotEmpty()) {
            group("wasmJs") {
              withWasmJs()
            }
          }
        }
      }
    }
  }

  sourceSets.configureEach {
    languageSettings.optIn("com.apollographql.apollo.annotations.ApolloExperimental")
    languageSettings.optIn("com.apollographql.apollo.annotations.ApolloInternal")
  }
}
