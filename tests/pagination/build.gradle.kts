import com.apollographql.apollo.annotations.ApolloExperimental

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.apollo)
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

    getByName("jvmTest") {
      dependencies {
        implementation(libs.slf4j.nop)
      }
    }
  }
}

apollo {
  service("embed") {
    packageName.set("embed")
    srcDir("src/commonMain/graphql/embed")
  }

  service("pagination.offsetBasedWithArray") {
    packageName.set("pagination.offsetBasedWithArray")
    srcDir("src/commonMain/graphql/pagination/offsetBasedWithArray")
    @OptIn(ApolloExperimental::class)
    generateDataBuilders.set(true)
  }
  service("pagination.offsetBasedWithPage") {
    packageName.set("pagination.offsetBasedWithPage")
    srcDir("src/commonMain/graphql/pagination/offsetBasedWithPage")
    @OptIn(ApolloExperimental::class)
    generateDataBuilders.set(true)
  }
  service("pagination.offsetBasedWithPageAndInput") {
    packageName.set("pagination.offsetBasedWithPageAndInput")
    srcDir("src/commonMain/graphql/pagination/offsetBasedWithPageAndInput")
    @OptIn(ApolloExperimental::class)
    generateDataBuilders.set(true)
  }
  service("pagination.cursorBased") {
    packageName.set("pagination.cursorBased")
    srcDir("src/commonMain/graphql/pagination/cursorBased")
    @OptIn(ApolloExperimental::class)
    generateDataBuilders.set(true)
  }
  service("pagination.connection") {
    packageName.set("pagination.connection")
    srcDir("src/commonMain/graphql/pagination/connection")
    @OptIn(ApolloExperimental::class)
    generateDataBuilders.set(true)
  }
  service("pagination.connectionWithNodes") {
    packageName.set("pagination.connectionWithNodes")
    srcDir("src/commonMain/graphql/pagination/connectionWithNodes")
    @OptIn(ApolloExperimental::class)
    generateDataBuilders.set(true)
  }
  service("pagination.connectionProgrammatic") {
    packageName.set("pagination.connectionProgrammatic")
    srcDir("src/commonMain/graphql/pagination/connectionProgrammatic")
    @OptIn(ApolloExperimental::class)
    generateDataBuilders.set(true)
  }
}
