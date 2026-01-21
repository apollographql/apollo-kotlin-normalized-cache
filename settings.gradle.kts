pluginManagement {
  includeBuild("build-logic")
}

plugins {
  id("com.gradle.develocity") version "4.2.2" // sync with libraries.toml
  id("com.gradle.common-custom-user-data-gradle-plugin") version "2.4.0"
}

apply(from = "gradle/repositories.gradle.kts")
apply(from = "gradle/ge.gradle")

include(
    "normalized-cache",
    "normalized-cache-sqlite",
    "normalized-cache-rocksdb",
    "normalized-cache-apollo-compiler-plugin",
    "test-utils",
)
