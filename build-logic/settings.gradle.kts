plugins {
  id("com.gradle.develocity") version "4.0.2" // sync with libraries.toml
  id("com.gradle.common-custom-user-data-gradle-plugin") version "2.3"
}

rootProject.name = "build-logic"

dependencyResolutionManagement {
  versionCatalogs {
    create("libs") {
      from(files("../gradle/libs.versions.toml"))
    }
  }
}

apply(from = "../gradle/repositories.gradle.kts")
apply(from = "../gradle/ge.gradle")
