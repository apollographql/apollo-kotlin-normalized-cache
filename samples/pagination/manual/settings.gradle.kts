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
    repositories {
        mavenLocal()
        maven {
            url = uri("https://s01.oss.sonatype.org/content/repositories/snapshots/")
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "Apollo Kotlin Pagination Sample"
include(":app")
