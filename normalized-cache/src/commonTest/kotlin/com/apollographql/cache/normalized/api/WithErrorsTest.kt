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
        "pinotQuerySearchPage": {
          "__typename": "PinotDefaultQuerySearchPage",
          "expires": "2025-10-14T02:24:02.921Z",
          "id": "xxxx",
          "version": null,
          "sessionId": "xxxx",
          "displayString": null,
          "trackingInfo": {
            "__typename": "PinotQuerySearchPageTrackingInfo",
            "requestId": "d3mno0gse3hvrjmnrrv0"
          },
          "eventListeners": [],
          "sections": {
            "__typename": "PinotSectionConnection",
            "totalCount": 24,
            "edges": [
              {
                "__typename": "PinotSectionEdge",
                "id": "xxxx",
                "cursor": "xxxx",
                "node": {
                  "__typename": "PinotCarouselSection",
                  "id": "xxxx",
                  "version": null,
                  "loggingData": {
                    "__typename": "PinotSectionLoggingData",
                    "trackId": 259449018,
                    "feature": "GamesRow"
                  },
                  "displayString": "Mobile Games",
                  "eventListeners": [],
                  "sectionTreatment": {
                    "__typename": "PinotStandardSectionTreatment",
                    "id": "xxxx",
                    "leadingDisplayStringIcon": ""
                  },
                  "entities": {
                    "__typename": "PinotEntityConnection",
                    "totalCount": 41,
                    "edges": [
                      {
                        "__typename": "PinotEntityEdge",
                        "cursor": "MA==",
                        "node": {
                          "__typename": "PinotAppIconEntityTreatment",
                          "displayString": "Some game",
                          "loggingData": {
                            "__typename": "PinotEntityLoggingData",
                            "impressionToken": null
                          },
                          "unifiedEntity": {
                            "__typename": "Game",
                            "unifiedEntityId": "Game:41",
                            "gameId": 41,
                            "tags": [
                              {
                                "__typename": "EntityTag",
                                "displayName": "Action",
                                "id": "xxxx",
                                "isDisplayable": true
                              }
                            ],
                            "subGame": null,
                            "detailsPageType": "GameDetailsPage"
                          },
                          "unifiedEntityId": "Game:41",
                          "contextualArtwork": {
                            "__typename": "PinotContextualArtwork",
                            "icon": {
                              "__typename": "Image",
                              "key": "xxxx",
                              "url": "https://example.com"
                            }
                          }
                        }
                      },
                      {
                        "__typename": "PinotEntityEdge",
                        "cursor": "MQ==",
                        "node": null
                      },
                      {
                        "__typename": "PinotEntityEdge",
                        "cursor": "Mg==",
                        "node": {
                          "__typename": "PinotAppIconEntityTreatment",
                          "displayString": "Some other game",
                          "loggingData": {
                            "__typename": "PinotEntityLoggingData",
                            "impressionToken": null
                          },
                          "unifiedEntity": {
                            "__typename": "Game",
                            "unifiedEntityId": "Game:42",
                            "gameId": 42,
                            "tags": [
                              {
                                "__typename": "EntityTag",
                                "displayName": "Action",
                                "id": "xxxx",
                                "isDisplayable": true
                              },
                              {
                                "__typename": "EntityTag",
                                "displayName": "Adventure",
                                "id": "xxxx",
                                "isDisplayable": true
                              }
                            ],
                            "subGame": null,
                            "detailsPageType": "GameDetailsPage"
                          },
                          "unifiedEntityId": "Game:42",
                          "contextualArtwork": {
                            "__typename": "PinotContextualArtwork",
                            "icon": {
                              "__typename": "Image",
                              "key": "xxxx",
                              "url": "https://example.com"
                            }
                          }
                        }
                      }
                    ],
                    "pageInfo": {
                      "__typename": "PageInfo",
                      "endCursor": "NQ==",
                      "hasNextPage": true,
                      "hasPreviousPage": false
                    }
                  }
                }
              }
            ],
            "pageInfo": {
              "__typename": "PageInfo",
              "startCursor": "xxxx",
              "endCursor": "xxxx",
              "hasNextPage": true,
              "hasPreviousPage": false
            }
          }
        }
      }      
      """.trimIndent()
    val data = BufferedSourceJsonReader(Buffer().writeUtf8(dataJson)).readAny() as Map<String, ApolloJsonElement>

    val errorsJson =
      // language=JSON
      """
      [
        {
          "message": "Client requested this field unifiedEntity to be non-null",
          "path": [
            "pinotQuerySearchPage",
            "sections",
            "edges",
            0,
            "node",
            "entities",
            "edges",
            1,
            "node",
            "unifiedEntity"
          ],
          "extensions": {
            "errorType": "NOT_FOUND",
            "origin": "graphlayer"
          }
        }
      ]
      """.trimIndent()
    val errors = BufferedSourceJsonReader(Buffer().writeUtf8(errorsJson)).readErrors()

    val dataWithErrors = data.withErrors(errors)
    assertErrorsEquals(
        expected = Error.Builder("Client requested this field unifiedEntity to be non-null")
            .path(
                listOf(
                    "pinotQuerySearchPage",
                    "sections",
                    "edges",
                    0,
                    "node",
                    "entities",
                    "edges",
                    1,
                    "node",
                    "unifiedEntity",
                ),
            )
            .putExtension("errorType", "NOT_FOUND")
            .putExtension("origin", "graphlayer")
            .build(),
        actual = dataWithErrors["pinotQuerySearchPage"].asMap()
        ["sections"].asMap()
        ["edges"].asList()[0].asMap()
        ["node"].asMap()
        ["entities"].asMap()
        ["edges"].asList()[1].asMap()
        ["node"] as Error,
    )
  }
}

private fun Any?.asMap(): Map<String, Any?> = this as Map<String, Any?>
private fun Any?.asList(): List<Any?> = this as List<Any?>
