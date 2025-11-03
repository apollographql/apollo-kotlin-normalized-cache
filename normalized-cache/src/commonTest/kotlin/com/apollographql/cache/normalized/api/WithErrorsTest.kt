@file:Suppress("UNCHECKED_CAST")

package com.apollographql.cache.normalized.api

import com.apollographql.apollo.api.Error
import com.apollographql.apollo.api.internal.readErrors
import com.apollographql.apollo.api.json.ApolloJsonElement
import com.apollographql.apollo.api.json.BufferedSourceJsonReader
import com.apollographql.apollo.api.json.readAny
import com.apollographql.cache.normalized.testing.assertErrorsEquals
import okio.Buffer
import kotlin.test.Test

class WithErrorsTest {
  @Test
  fun pathAtPresentPosition() {
    val dataJson =
      // language=JSON
      """
      {
        "hero": {
          "name": "R2-D2",
          "heroFriends": [
            {
              "id": "1000",
              "name": "Luke Skywalker"
            },
            {
              "id": "1002",
              "name": null
            },
            {
              "id": "1003",
              "name": "Leia Organa"
            }
          ]
        }
      }
      """.trimIndent()
    val data = BufferedSourceJsonReader(Buffer().writeUtf8(dataJson)).readAny() as Map<String, ApolloJsonElement>

    val errorsJson =
      // language=JSON
      """
      [
        {
          "message": "Name for character with ID 1002 could not be fetched.",
          "locations": [{"line": 6,"column": 7}],
          "path": ["hero", "heroFriends", 1, "name"]
        }
      ]
      """.trimIndent()
    val errors = BufferedSourceJsonReader(Buffer().writeUtf8(errorsJson)).readErrors()

    val dataWithErrors = data.withErrors(errors)
    assertErrorsEquals(
        expected = Error.Builder("Name for character with ID 1002 could not be fetched.")
            .locations(listOf(Error.Location(6, 7)))
            .path(listOf("hero", "heroFriends", 1, "name"))
            .build(),
        actual = dataWithErrors["hero"].asMap()["heroFriends"].asList()[1].asMap()["name"] as Error,
    )
  }

  @Test
  fun pathAtAbsentPositionInList() {
    val dataJson =
      // language=JSON
      """
      {
        "hero": {
          "name": "R2-D2",
          "heroFriends": [
            {
              "id": "1000",
              "name": "Luke Skywalker"
            },
            null,
            {
              "id": "1003",
              "name": "Leia Organa"
            }
          ]
        }
      }
      """.trimIndent()
    val data = BufferedSourceJsonReader(Buffer().writeUtf8(dataJson)).readAny() as Map<String, ApolloJsonElement>

    val errorsJson =
      // language=JSON
      """
      [
        {
          "message": "Name for character with ID 1002 could not be fetched.",
          "locations": [{"line": 6,"column": 7}],
          "path": ["hero", "heroFriends", 1, "name"]
        }
      ]
      """.trimIndent()
    val errors = BufferedSourceJsonReader(Buffer().writeUtf8(errorsJson)).readErrors()

    val dataWithErrors = data.withErrors(errors)
    assertErrorsEquals(
        expected = Error.Builder("Name for character with ID 1002 could not be fetched.")
            .locations(listOf(Error.Location(6, 7)))
            .path(listOf("hero", "heroFriends", 1, "name"))
            .build(),
        actual = dataWithErrors["hero"].asMap()["heroFriends"].asList()[1] as Error,
    )
  }

  @Test
  fun pathAtAbsentPositionInObject() {
    val dataJson =
      // language=JSON
      """
      {
        "foo": null
      }
      """.trimIndent()
    val data = BufferedSourceJsonReader(Buffer().writeUtf8(dataJson)).readAny() as Map<String, ApolloJsonElement>

    val errorsJson =
      // language=JSON
      """
      [
        {
          "message": "Baz is null",
          "path": ["foo", "bar", 1, "baz"]
        }
      ]
      """.trimIndent()
    val errors = BufferedSourceJsonReader(Buffer().writeUtf8(errorsJson)).readErrors()

    val dataWithErrors = data.withErrors(errors)
    assertErrorsEquals(
        expected = Error.Builder("Baz is null")
            .path(listOf("foo", "bar", 1, "baz"))
            .build(),
        actual = dataWithErrors["foo"] as Error,
    )
  }
}

private fun Any?.asMap(): Map<String, Any?> = this as Map<String, Any?>
private fun Any?.asList(): List<Any?> = this as List<Any?>
