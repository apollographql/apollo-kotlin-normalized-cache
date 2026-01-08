package com.apollographql.cache.normalized.disk

import com.apollographql.cache.normalized.api.NormalizedCacheFactory

/**
 * Creates a new [NormalizedCacheFactory] that uses a persistent cache based on files stored on disk.
 *
 * @param name the name or path where to store the files.
 * The files will be stored in a platform specific folder:
 * - on Android, files will be inside [Context.getCacheDir()](https://developer.android.com/reference/android/content/Context#getCacheDir())`/name`
 * - on the JVM, files will be inside `System.getProperty("user.home")` `/.apollo/name`
 * - on MacOS, files will be inside `Application Support/databases/name`
 *
 * It can also be an absolute path to a file if `name` starts with a `/`.
 *
 * Default: "apollo"
 */
expect fun DiskNormalizedCacheFactory(name: String = "apollo"): NormalizedCacheFactory
