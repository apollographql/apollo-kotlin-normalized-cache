# Welcome

This is [Apollo Kotlin](https://github.com/apollographql/apollo-kotlin)'s Normalized Cache. 

For an introduction please read the Normalized Cache [documentation](https://www.apollographql.com/docs/kotlin/caching/normalized-cache).

Note: the Normalized Cache used to be part of the [Apollo Kotlin main repository](https://github.com/apollographql/apollo-kotlin).
It is now hosted in a dedicated repository and published at its own cadence and versioning scheme.

## Use in your project

> During the alpha phase, the API is still subject to change, although we will try to make changes in non-breaking ways.
> For now it is recommended to experiment with this library in non-critical projects/modules, or behind a feature flag.

{style="warning"}

Add the dependencies to your project.

```kotlin
// build.gradle.kts
dependencies {
  // For the memory cache
  implementation("com.apollographql.cache:normalized-cache:%latest_version%")

  // For the SQL cache
  implementation("com.apollographql.cache:normalized-cache-sqlite:%latest_version%")
}
```

If you were using the classic Normalized Cache before, please consult the [migration guide](migration-guide.md). 
