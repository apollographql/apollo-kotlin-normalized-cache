plugins {
  alias(libs.plugins.kotlin.jvm)
  id("com.apollographql.apollo").version("4.2.0")
}

apollo {
  service("service") {
    packageName.set("com.example")
    // We use + as a version here to avoid having to share the version with the main build
    // There is also an exclusiveContent filter installed to make sure we resolve the local artifacts
    plugin("com.apollographql.cache:normalized-cache-apollo-compiler-plugin:+") {
      argument("com.apollographql.cache.packageName", packageName.get())
    }
  }
}

dependencies {
  implementation("com.apollographql.apollo:apollo-api")
  implementation("com.apollographql.cache:normalized-cache:+")
  testImplementation(libs.kotlin.test)
}
