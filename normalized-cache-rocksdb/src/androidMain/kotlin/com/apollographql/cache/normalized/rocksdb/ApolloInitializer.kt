package com.apollographql.cache.normalized.rocksdb

import android.content.Context
import androidx.startup.Initializer

class ApolloInitializer : Initializer<Unit> {
  override fun create(context: Context) {
    Companion.context = context
    return
  }

  override fun dependencies(): MutableList<Class<out Initializer<*>>> {
    return mutableListOf()
  }

  companion object {
    //noinspection StaticFieldLeak
    internal lateinit var context: Context
  }
}
