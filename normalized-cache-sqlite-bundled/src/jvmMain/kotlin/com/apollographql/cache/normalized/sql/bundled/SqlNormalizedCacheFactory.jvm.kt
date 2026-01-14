package com.apollographql.cache.normalized.sql.bundled

import java.io.File

internal actual fun getFileName(name: String?): String? {
  return when {
    name == null -> {
      null
    }

    name.startsWith("/") -> {
      name
    }

    else -> {
      val dir = "${System.getProperty("user.home")}/.apollo"
      File(dir).mkdirs()
      "$dir/$name"
    }
  }
}
