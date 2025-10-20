@file:OptIn(ApolloExperimental::class)

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

    plugin("com.apollographql.cache:normalized-cache-apollo-compiler-plugin") {
      argument("com.apollographql.cache.packageName", packageName.get())
    }
  }

  service("pagination.offsetBasedWithArray") {
    packageName.set("pagination.offsetBasedWithArray")
    srcDir("src/commonMain/graphql/pagination/offsetBasedWithArray")
    generateDataBuilders.set(true)

    plugin("com.apollographql.cache:normalized-cache-apollo-compiler-plugin") {
      argument("com.apollographql.cache.packageName", packageName.get())
    }
  }
  service("pagination.offsetBasedWithPage") {
    packageName.set("pagination.offsetBasedWithPage")
    srcDir("src/commonMain/graphql/pagination/offsetBasedWithPage")
    generateDataBuilders.set(true)

    plugin("com.apollographql.cache:normalized-cache-apollo-compiler-plugin") {
      argument("com.apollographql.cache.packageName", packageName.get())
    }
  }
  service("pagination.offsetBasedWithPageAndInput") {
    packageName.set("pagination.offsetBasedWithPageAndInput")
    srcDir("src/commonMain/graphql/pagination/offsetBasedWithPageAndInput")
    generateDataBuilders.set(true)

    plugin("com.apollographql.cache:normalized-cache-apollo-compiler-plugin") {
      argument("com.apollographql.cache.packageName", packageName.get())
    }
  }
  service("pagination.cursorBased") {
    packageName.set("pagination.cursorBased")
    srcDir("src/commonMain/graphql/pagination/cursorBased")
    generateDataBuilders.set(true)

    plugin("com.apollographql.cache:normalized-cache-apollo-compiler-plugin") {
      argument("com.apollographql.cache.packageName", packageName.get())
    }
  }
  service("pagination.connection") {
    packageName.set("pagination.connection")
    srcDir("src/commonMain/graphql/pagination/connection")
    generateDataBuilders.set(true)

    plugin("com.apollographql.cache:normalized-cache-apollo-compiler-plugin") {
      argument("com.apollographql.cache.packageName", packageName.get())
    }
  }
  service("pagination.connectionWithNodes") {
    packageName.set("pagination.connectionWithNodes")
    srcDir("src/commonMain/graphql/pagination/connectionWithNodes")
    generateDataBuilders.set(true)

    plugin("com.apollographql.cache:normalized-cache-apollo-compiler-plugin") {
      argument("com.apollographql.cache.packageName", packageName.get())
    }
  }
  service("pagination.connectionProgrammaticConnections") {
    packageName.set("pagination.connectionProgrammaticConnections")
    srcDir("src/commonMain/graphql/pagination/connectionProgrammaticConnections")
    generateDataBuilders.set(true)

    plugin("com.apollographql.cache:normalized-cache-apollo-compiler-plugin") {
      argument("com.apollographql.cache.packageName", packageName.get())
    }
  }
  service("pagination.connectionProgrammaticTypePolicies") {
    packageName.set("pagination.connectionProgrammaticTypePolicies")
    srcDir("src/commonMain/graphql/pagination/connectionProgrammaticTypePolicies")
    generateDataBuilders.set(true)

    plugin("com.apollographql.cache:normalized-cache-apollo-compiler-plugin") {
      argument("com.apollographql.cache.packageName", packageName.get())
    }
  }
}
