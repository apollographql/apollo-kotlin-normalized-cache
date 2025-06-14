plugins {
  alias(libs.plugins.kotlin.jvm)
  id("com.apollographql.apollo").version("4.3.0")
}

apollo {
  service("service") {
    packageName.set("com.example")
    plugin("com.apollographql.cache:normalized-cache-apollo-compiler-plugin") {
      argument("packageName", packageName.get())
    }
  }
}
