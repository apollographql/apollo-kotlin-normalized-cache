import com.apollographql.apollo.annotations.ApolloExperimental

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  id("com.apollographql.apollo")
}

kotlin {
  configureKmp(
      withJs = emptySet(),
      withWasm = emptySet(),
      withAndroid = false,
      withApple = AppleTargets.Host,
  )

  sourceSets {
    getByName("commonMain") {
      dependencies {
        implementation(libs.apollo.runtime)
        implementation("com.apollographql.cache:normalized-cache-sqlite")
        implementation("com.apollographql.cache:normalized-cache-rocksdb")
      }
    }

    getByName("commonTest") {
      dependencies {
        implementation("com.apollographql.cache:test-utils")
        implementation(libs.apollo.mockserver)
        implementation(libs.kotlin.test)
        implementation(libs.turbine)
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
  service("programmatic") {
    packageName.set("programmatic")
    srcDir("src/commonMain/graphql/programmatic")
  }

  service("declarative") {
    packageName.set("declarative")
    srcDir("src/commonMain/graphql/declarative")

    @OptIn(ApolloExperimental::class)
    plugin("com.apollographql.cache:normalized-cache-apollo-compiler-plugin") {
      argument("com.apollographql.cache.packageName", packageName.get())
    }
  }

  service("doNotStore") {
    packageName.set("donotstore")
    srcDir("src/commonMain/graphql/doNotStore")

    @OptIn(ApolloExperimental::class)
    plugin("com.apollographql.cache:normalized-cache-apollo-compiler-plugin") {
      argument("com.apollographql.cache.packageName", packageName.get())
    }
  }
}
