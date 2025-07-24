import com.apollographql.apollo.annotations.ApolloExperimental

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.apollo)
}

kotlin {
  configureKmp(
      withJs = setOf(JsAndWasmEnvironment.Browser),
      withWasm = setOf(JsAndWasmEnvironment.Browser),
      withAndroid = false,
      withApple = AppleTargets.None,
  )

  sourceSets {
    getByName("commonMain") {
      dependencies {
        implementation(libs.apollo.runtime)
        implementation("com.apollographql.cache:normalized-cache-sqlite")
      }
    }

    getByName("commonTest") {
      dependencies {
        implementation(libs.kotlin.test)
        implementation(libs.apollo.testing.support)
        implementation("com.apollographql.cache:test-utils")
      }
    }

    getByName("jsCommonMain") {
      dependencies {
        implementation(devNpm("copy-webpack-plugin", libs.versions.copyWebpackPlugin.get()))
      }
    }

    getByName("jsCommonTest") {
      dependencies {
        implementation(npm("@cashapp/sqldelight-sqljs-worker", libs.versions.sqldelight.get()))
        implementation(npm("sql.js", libs.versions.sqlJs.get()))
      }
    }
  }
}

apollo {
  service("service") {
    packageName.set("test")

    @OptIn(ApolloExperimental::class)
    plugin("com.apollographql.cache:normalized-cache-apollo-compiler-plugin") {
      argument("com.apollographql.cache.packageName", packageName.get())
    }
  }
}
