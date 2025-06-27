listOf(pluginManagement.repositories, dependencyResolutionManagement.repositories).forEach {
  it.apply {
//    mavenLocal()
//    maven("https://s01.oss.sonatype.org/content/repositories/snapshots/")
//    maven("https://storage.googleapis.com/apollo-previews/m2/")
    mavenCentral()
    google()
    gradlePluginPortal()
    maven("https://storage.googleapis.com/gradleup/m2")
  }
}

pluginManagement.repositories {
  exclusiveContent {
    forRepository { maven("https://storage.googleapis.com/gradleup/m2") }
    filter {
      includeGroup("com.gradleup.librarian")
    }
  }
}
