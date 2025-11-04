package com.apollographql.cache.normalized.testing

import com.apollographql.apollo.api.Error
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Helps using assertEquals on Errors.
 */
private data class ComparableError(
    val message: String,
    val locations: List<ComparableLocation>?,
    val path: List<Any>?,
)

private data class ComparableLocation(
    val line: Int,
    val column: Int,
)

private fun Error.toComparableError(): ComparableError = ComparableError(
    message = message,
    locations = locations?.map { location -> ComparableLocation(location.line, location.column) },
    path = path,
)

fun assertErrorsEquals(expected: Iterable<Error>?, actual: Iterable<Error>?) {
  val expectedList = expected?.map(Error::toComparableError)
  val actualList = actual?.map(Error::toComparableError)
  assertContentEquals(expectedList, actualList)
}

fun assertErrorsEquals(expected: Error?, actual: Error?) {
  if (expected == null) {
    assertNull(actual)
    return
  }
  assertNotNull(actual)
  assertErrorsEquals(listOf(expected), listOf(actual))
}
