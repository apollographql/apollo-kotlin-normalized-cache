dependencyResolutionManagement {
  repositories {
    exclusiveContent {
      // Make sure the cache artifacts are the ones from the local maven repo
      forRepository{
        maven("../../../build/m2")
      }
      filter {
        includeGroup("com.apollographql.cache")
      }
    }

    mavenCentral()
  }
}

