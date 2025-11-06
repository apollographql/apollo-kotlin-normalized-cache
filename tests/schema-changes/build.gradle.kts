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
      }
    }

    getByName("commonTest") {
      dependencies {
        implementation(libs.apollo.testing.support)
        implementation("com.apollographql.cache:test-utils")
        implementation(libs.apollo.mockserver)
        implementation(libs.kotlin.test)
      }
    }
  }
}

apollo {
  service("service") {
    srcDir("src/commonMain/graphql/service")
    packageName.set("schema.changes")
    codegenModels.set("responseBased")
  }

  service("schemav1") {
    srcDir("src/commonMain/graphql/schemav1")
    packageName.set("schemav1")
  }

  service("schemav2") {
    srcDir("src/commonMain/graphql/schemav2")
    packageName.set("schemav2")
  }

}
