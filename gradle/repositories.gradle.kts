listOf(pluginManagement.repositories, dependencyResolutionManagement.repositories).forEach {
  it.apply {
//    maven("https://s01.oss.sonatype.org/content/repositories/snapshots/")
    mavenCentral()
    google()
    gradlePluginPortal()
    maven("https://storage.googleapis.com/gradleup/m2")
  }
}
