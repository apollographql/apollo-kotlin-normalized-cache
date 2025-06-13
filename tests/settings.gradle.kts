includeBuild("../")

pluginManagement {
  includeBuild("../build-logic")
}

plugins {
  id("com.gradle.develocity") version "4.0.2" // sync with libraries.toml
  id("com.gradle.common-custom-user-data-gradle-plugin") version "2.3"
}

rootProject.name = "apollo-kotlin-normalized-cache-tests"

apply(from = "../gradle/repositories.gradle.kts")
apply(from = "../gradle/ge.gradle")

// Include all tests
rootProject.projectDir
  .listFiles()!!
  .filter { it.isDirectory && File(it, "build.gradle.kts").exists() }
  .forEach {
    include(it.name)
  }
