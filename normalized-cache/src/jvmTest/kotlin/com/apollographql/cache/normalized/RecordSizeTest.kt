package com.apollographql.cache.normalized

import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.api.Record
import com.apollographql.cache.normalized.memory.internal.RecordWeigher
import com.apollographql.cache.normalized.testing.runTest
import org.openjdk.jol.info.GraphLayout
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

class RecordSizeTest {
  @Test
  fun reachableSize() = runTest {
    val random = Random(0)
    val alphabet = buildList {
      addAll('a'..'z')
      addAll('A'..'Z')
      addAll('0'..'9')
    }
    val fields = (0 until 100).associate { i ->
      i.toString().padStart(2, '0') to String(CharArray(10) { alphabet.random(random) })
    }
    val record = Record(key = CacheKey("record"), fields = fields)

    val actual = GraphLayout.parseInstance(record).totalSize()
    val computed = RecordWeigher.calculateBytes(record)

    // allow 10% error
    assertTrue(abs(actual - computed).toDouble()/((actual + computed)/2) < 0.10)
  }
}
