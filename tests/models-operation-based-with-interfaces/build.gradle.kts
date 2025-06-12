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
    @OptIn(ApolloExperimental::class)
    generateDataBuilders.set(true)
    generateFragmentImplementations.set(true)
    codegenModels.set("experimental_operationBasedWithInterfaces")
  }
}
