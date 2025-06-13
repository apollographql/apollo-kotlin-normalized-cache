listOf(pluginManagement.repositories, dependencyResolutionManagement.repositories).forEach {
  it.apply {
//    mavenLocal()
//    maven("https://s01.oss.sonatype.org/content/repositories/snapshots/")
//    maven("https://storage.googleapis.com/apollo-previews/m2/")
    mavenLocal()
    mavenCentral()
    google()
    gradlePluginPortal()
    maven("https://storage.googleapis.com/gradleup/m2")
  }
}
