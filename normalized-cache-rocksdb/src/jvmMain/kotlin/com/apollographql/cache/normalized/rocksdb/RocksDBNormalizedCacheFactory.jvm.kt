package com.apollographql.cache.normalized.rocksdb

import java.io.File

internal actual fun getFileName(name: String): String {
  return if (name.startsWith("/")) {
    name
  } else {
    val dir = "${System.getProperty("user.home")}/.apollo"
    File(dir).mkdirs()
    "$dir/$name"
  }
}
