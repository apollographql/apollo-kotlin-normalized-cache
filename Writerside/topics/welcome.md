# Welcome

This is [Apollo Kotlin](https://github.com/apollographql/apollo-kotlin)'s Normalized Cache. 

For an introduction please read the Normalized Cache [documentation](https://www.apollographql.com/docs/kotlin/caching/normalized-cache).

The Normalized Cache is now hosted in this dedicated repository and published at its own cadence and versioning scheme.

Compared to the previous version, this library brings:

- [Pagination support](pagination-home.md)
- [](cache-control.md) (a.k.a. Time to live or expiration)
- [](garbage-collection.md), and [trimming](trimming.md)
- Partial results from the cache
- API simplifications
- Key scope support
- SQL cache improved performance

The Normalized Cache in the [Apollo Kotlin main repository](https://github.com/apollographql/apollo-kotlin) will not receive new features - they
are added here instead. In the future, the main repository Normalized Cache will be deprecated and then removed.

## Use in your project

> During the alpha phase, the API is still subject to change, although we will try to make changes in non-breaking ways.
> For now it is recommended to experiment with this library in non-critical projects/modules, or behind a feature flag.

{style="warning"}

1. Add the dependencies to your project

```kotlin
// build.gradle.kts
dependencies {
  // For the memory cache
  implementation("com.apollographql.cache:normalized-cache:%latest_version%")

  // For the SQL cache
  implementation("com.apollographql.cache:normalized-cache-sqlite:%latest_version%")
}
```

2. Configure the compiler plugin

```kotlin
// build.gradle.kts
apollo {
  service("service") {
    // ...

    // Add this
    plugin("com.apollographql.cache:normalized-cache-apollo-compiler-plugin:%latest_version%") {
      argument("packageName", packageName.get())
    }
  }
}
```

If you're already using the main repository Normalized Cache, please consult the [migration guide](migration-guide.md). 
