pluginManagement {
    repositories {
        maven("https://storage.googleapis.com/apollo-previews/m2/")
        maven {
            url = uri("https://s01.oss.sonatype.org/content/repositories/snapshots/")
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        maven("https://storage.googleapis.com/apollo-previews/m2/")
        maven {
            url = uri("https://s01.oss.sonatype.org/content/repositories/snapshots/")
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "Apollo Kotlin Pagination Sample"
include(":app")
