package com.apollographql.cache.normalized.rocksdb

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

internal actual fun getFileName(name: String): String {
  return if (name.startsWith("/")) {
    name
  } else {
    "${databasesPath()}/$name"
  }
}

@OptIn(UnsafeNumber::class, ExperimentalForeignApi::class)
private fun databasesPath(): String {
  val paths = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
  val documentsDirectory = paths[0] as String
  val databaseDirectory = "$documentsDirectory/databases"
  val fileManager = NSFileManager.defaultManager()
  if (!fileManager.fileExistsAtPath(databaseDirectory)) {
    fileManager.createDirectoryAtPath(databaseDirectory, true, null, null)
  }
  return databaseDirectory
}
