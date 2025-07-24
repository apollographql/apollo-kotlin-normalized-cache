import com.apollographql.apollo.annotations.ApolloExperimental

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
    getByName("commonMain") {
      dependencies {
        implementation(libs.apollo.runtime)
        implementation("com.apollographql.cache:normalized-cache")
      }
    }

    getByName("concurrentMain") {
      dependencies {
        implementation("com.apollographql.cache:normalized-cache-sqlite")
      }
    }

    getByName("commonTest") {
      dependencies {
        implementation("com.apollographql.cache:test-utils")
        implementation(libs.apollo.mockserver)
        implementation(libs.kotlin.test)
        implementation("com.apollographql.cache:test-utils")
      }
    }

    getByName("jvmTest") {
      dependencies {
        implementation(libs.slf4j.nop)
      }
    }
  }
}

apollo {
  service("1") {
    srcDir("src/commonMain/graphql/1")
    packageName.set("com.example.one")
  }
  service("2") {
    srcDir("src/commonMain/graphql/2")
    packageName.set("com.example.two")
  }
  service("3") {
    srcDir("src/commonMain/graphql/3")
    packageName.set("com.example.three")

    @OptIn(ApolloExperimental::class)
    plugin("com.apollographql.cache:normalized-cache-apollo-compiler-plugin") {
      argument("com.apollographql.cache.packageName", packageName.get())
    }
  }
  service("4") {
    srcDir("src/commonMain/graphql/4")
    packageName.set("com.example.four")
  }
}
