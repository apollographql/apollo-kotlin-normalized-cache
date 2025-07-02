pluginManagement {
  includeBuild("build-logic")
}

plugins {
  id("com.gradle.develocity") version "4.0.2" // sync with libraries.toml
  id("com.gradle.common-custom-user-data-gradle-plugin") version "2.3"
}

apply(from = "gradle/repositories.gradle.kts")
apply(from = "gradle/ge.gradle")

include(
    "test-utils",
    "normalized-cache",
    "normalized-cache-sqlite",
    "normalized-cache-definitions",
    "normalized-cache-apollo-compiler-plugin",
)
