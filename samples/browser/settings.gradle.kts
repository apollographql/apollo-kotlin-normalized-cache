pluginManagement {
  repositories {
    mavenLocal()
    maven {
      url = uri("https://s01.oss.sonatype.org/content/repositories/snapshots/")
    }
    google()
    mavenCentral()
    gradlePluginPortal()
  }
}
dependencyResolutionManagement {
  @Suppress("UnstableApiUsage")
  repositories {
    mavenLocal()
    maven {
      url = uri("https://s01.oss.sonatype.org/content/repositories/snapshots/")
    }
    google()
    mavenCentral()
  }
}

rootProject.name = "BrowserSample"
include(":app")
