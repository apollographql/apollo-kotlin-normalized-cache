package fixtures

const val HeroTypeDependentAliasedFieldResponseHuman = """
{
  "data": {
    "hero": {
      "__typename": "Human",
      "property": "Tatooine"
    }
  }
}
"""

const val JsonScalar = """
{
  "data": {
    "json": {
      "obj": {
        "key": "value"
      },
      "list": [
        0,
        1,
        2
      ]
    }
  }
}
"""

const val EpisodeHeroNameResponseNameChange = """
{
  "data": {
    "hero": {
      "__typename": "Droid",
      "id": "2001",
      "name": "Artoo"
    }
  }
}
"""

const val HeroParentTypeDependentFieldDroidResponse = """
{
  "data": {
    "hero": {
      "__typename": "Droid",
      "name": "R2-D2",
      "friends": [
        {
          "__typename": "Human",
          "name": "Luke Skywalker",
          "height": 1.72
        },
        {
          "__typename": "Human",
          "name": "Han Solo",
          "height": 1.8
        },
        {
          "__typename": "Human",
          "name": "Leia Organa",
          "height": 1.5
        }
      ]
    }
  }
}
"""

const val UpdateReviewResponse = """
{
  "data": {
    "updateReview": {
      "__typename": "Review",
      "id": "empireReview2",
      "stars": 4,
      "commentary": "Not Bad"
    }
  }
}
"""

const val EpisodeHeroNameResponseWithId = """
{
  "data": {
    "hero": {
      "__typename": "Droid",
      "id": "2001",
      "name": "R2-D2"
    }
  }
}
"""

const val AllPlanetsNullableField = """
{
  "data": {
    "allPlanets": {
      "__typename": "PlanetsConnection",
      "planets": [
        {
          "__typename": "Planet",
          "name": "Tatooine",
          "climates": [
            "arid"
          ],
          "surfaceWater": 1,
          "filmConnection": {
            "__typename": "PlanetFilmsConnection",
            "totalCount": 5,
            "films": [
              {
                "__typename": "Film",
                "title": "A New Hope",
                "producers": [
                  "Gary Kurtz",
                  "Rick McCallum"
                ]
              },
              {
                "__typename": "Film",
                "title": "Return of the Jedi",
                "producers": [
                  "Howard G. Kazanjian",
                  "George Lucas",
                  "Rick McCallum"
                ]
              },
              {
                "__typename": "Film",
                "title": "The Phantom Menace",
                "producers": [
                  "Rick McCallum"
                ]
              },
              {
                "__typename": "Film",
                "title": "Attack of the Clones",
                "producers": [
                  "Rick McCallum"
                ]
              },
              {
                "__typename": "Film",
                "title": "Revenge of the Sith",
                "producers": [
                  "Rick McCallum"
                ]
              }
            ]
          }
        },
        {
          "__typename": "Planet",
          "name": "Alderaan",
          "climates": [
            "temperate"
          ],
          "surfaceWater": 40,
          "filmConnection": {
            "__typename": "PlanetFilmsConnection",
            "totalCount": 2,
            "films": [
              {
                "__typename": "Film",
                "title": "A New Hope",
                "producers": [
                  "Gary Kurtz",
                  "Rick McCallum"
                ]
              },
              {
                "__typename": "Film",
                "title": "Revenge of the Sith",
                "producers": [
                  "Rick McCallum"
                ]
              }
            ]
          }
        },
        {
          "__typename": "Planet",
          "name": "Yavin IV",
          "climates": [
            "temperate",
            "tropical"
          ],
          "surfaceWater": 8,
          "filmConnection": {
            "__typename": "PlanetFilmsConnection",
            "totalCount": 1,
            "films": [
              {
                "__typename": "Film",
                "title": "A New Hope",
                "producers": [
                  "Gary Kurtz",
                  "Rick McCallum"
                ]
              }
            ]
          }
        },
        {
          "__typename": "Planet",
          "name": "Hoth",
          "climates": [
            "frozen"
          ],
          "surfaceWater": 100,
          "filmConnection": {
            "__typename": "PlanetFilmsConnection",
            "totalCount": 1,
            "films": [
              {
                "__typename": "Film",
                "title": "The Empire Strikes Back",
                "producers": [
                  "Gary Kutz",
                  "Rick McCallum"
                ]
              }
            ]
          }
        },
        {
          "__typename": "Planet",
          "name": "Dagobah",
          "climates": [
            "murky"
          ],
          "surfaceWater": 8,
          "filmConnection": {
            "__typename": "PlanetFilmsConnection",
            "totalCount": 3,
            "films": [
              {
                "__typename": "Film",
                "title": "The Empire Strikes Back",
                "producers": [
                  "Gary Kutz",
                  "Rick McCallum"
                ]
              },
              {
                "__typename": "Film",
                "title": "Return of the Jedi",
                "producers": [
                  "Howard G. Kazanjian",
                  "George Lucas",
                  "Rick McCallum"
                ]
              },
              {
                "__typename": "Film",
                "title": "Revenge of the Sith",
                "producers": [
                  "Rick McCallum"
                ]
              }
            ]
          }
        },
        {
          "__typename": "Planet",
          "name": "Bespin",
          "climates": [
            "temperate"
          ],
          "surfaceWater": 0,
          "filmConnection": {
            "__typename": "PlanetFilmsConnection",
            "totalCount": 1,
            "films": [
              {
                "__typename": "Film",
                "title": "The Empire Strikes Back",
                "producers": [
                  "Gary Kutz",
                  "Rick McCallum"
                ]
              }
            ]
          }
        },
        {
          "__typename": "Planet",
          "name": "Endor",
          "climates": [
            "temperate"
          ],
          "surfaceWater": 8,
          "filmConnection": {
            "__typename": "PlanetFilmsConnection",
            "totalCount": 1,
            "films": [
              {
                "__typename": "Film",
                "title": "Return of the Jedi",
                "producers": [
                  "Howard G. Kazanjian",
                  "George Lucas",
                  "Rick McCallum"
                ]
              }
            ]
          }
        },
        {
          "__typename": "Planet",
          "name": "Naboo",
          "climates": [
            "temperate"
          ],
          "surfaceWater": 12,
          "filmConnection": {
            "__typename": "PlanetFilmsConnection",
            "totalCount": 4,
            "films": [
              {
                "__typename": "Film",
                "title": "Return of the Jedi",
                "producers": [
                  "Howard G. Kazanjian",
                  "George Lucas",
                  "Rick McCallum"
                ]
              },
              {
                "__typename": "Film",
                "title": "The Phantom Menace",
                "producers": [
                  "Rick McCallum"
                ]
              },
              {
                "__typename": "Film",
                "title": "Attack of the Clones",
                "producers": [
                  "Rick McCallum"
                ]
              },
              {
                "__typename": "Film",
                "title": "Revenge of the Sith",
                "producers": [
                  "Rick McCallum"
                ]
              }
            ]
          }
        },
        {
          "__typename": "Planet",
          "name": "Coruscant",
          "climates": [
            "temperate"
          ],
          "surfaceWater": null,
          "filmConnection": {
            "__typename": "PlanetFilmsConnection",
            "totalCount": 4,
            "films": [
              {
                "__typename": "Film",
                "title": "Return of the Jedi",
                "producers": [
                  "Howard G. Kazanjian",
                  "George Lucas",
                  "Rick McCallum"
                ]
              },
              {
                "__typename": "Film",
                "title": "The Phantom Menace",
                "producers": [
                  "Rick McCallum"
                ]
              },
              {
                "__typename": "Film",
                "title": "Attack of the Clones",
                "producers": [
                  "Rick McCallum"
                ]
              },
              {
                "__typename": "Film",
                "title": "Revenge of the Sith",
                "producers": [
                  "Rick McCallum"
                ]
              }
            ]
          }
        },
        {
          "__typename": "Planet",
          "name": "Kamino",
          "climates": [
            "temperate"
          ],
          "surfaceWater": 100,
          "filmConnection": {
            "__typename": "PlanetFilmsConnection",
            "totalCount": 1,
            "films": [
              {
                "__typename": "Film",
                "title": "Attack of the Clones",
                "producers": [
                  "Rick McCallum"
                ]
              }
            ]
          }
        },
        {
          "__typename": "Planet",
          "name": "Geonosis",
          "climates": [
            "temperate",
            "arid"
          ],
          "surfaceWater": 5,
          "filmConnection": {
            "__typename": "PlanetFilmsConnection",
            "totalCount": 1,
            "films": [
              {
                "__typename": "Film",
                "title": "Attack of the Clones",
                "producers": [
                  "Rick McCallum"
                ]
              }
            ]
          }
        },
        {
          "__typename": "Planet",
          "name": "Utapau",
          "climates": [
            "temperate",
            "arid",
            "windy"
          ],
          "surfaceWater": 0.9,
          "filmConnection": {
            "__typename": "PlanetFilmsConnection",
            "totalCount": 1,
            "films": [
              {
                "__typename": "Film",
                "title": "Revenge of the Sith",
                "producers": [
                  "Rick McCallum"
                ]
              }
            ]
          }
        },
        {
          "__typename": "Planet",
          "name": "Mustafar",
          "climates": [
            "hot"
          ],
          "surfaceWater": 0,
          "filmConnection": {
            "__typename": "PlanetFilmsConnection",
            "totalCount": 1,
            "films": [
              {
                "__typename": "Film",
                "title": "Revenge of the Sith",
                "producers": [
                  "Rick McCallum"
                ]
              }
            ]
          }
        },
        {
          "__typename": "Planet",
          "name": "Kashyyyk",
          "climates": [
            "tropical"
          ],
          "surfaceWater": 60,
          "filmConnection": {
            "__typename": "PlanetFilmsConnection",
            "totalCount": 1,
            "films": [
              {
                "__typename": "Film",
                "title": "Revenge of the Sith",
                "producers": [
                  "Rick McCallum"
                ]
              }
            ]
          }
        },
        {
          "__typename": "Planet",
          "name": "Polis Massa",
          "climates": [
            "artificial temperate"
          ],
          "surfaceWater": 0,
          "filmConnection": {
            "__typename": "PlanetFilmsConnection",
            "totalCount": 1,
            "films": [
              {
                "__typename": "Film",
                "title": "Revenge of the Sith",
                "producers": [
                  "Rick McCallum"
                ]
              }
            ]
          }
        },
        {
          "__typename": "Planet",
          "name": "Mygeeto",
          "climates": [
            "frigid"
          ],
          "surfaceWater": null,
          "filmConnection": {
            "__typename": "PlanetFilmsConnection",
            "totalCount": 1,
            "films": [
              {
                "__typename": "Film",
                "title": "Revenge of the Sith",
                "producers": [
                  "Rick McCallum"
                ]
              }
            ]
          }
        },
        {
          "__typename": "Planet",
          "name": "Felucia",
          "climates": [
            "hot",
            "humid"
          ],
          "surfaceWater": null,
          "filmConnection": {
            "__typename": "PlanetFilmsConnection",
            "totalCount": 1,
            "films": [
              {
                "__typename": "Film",
                "title": "Revenge of the Sith",
                "producers": [
                  "Rick McCallum"
                ]
              }
            ]
          }
        },
        {
          "__typename": "Planet",
          "name": "Cato Neimoidia",
          "climates": [
            "temperate",
            "moist"
          ],
          "surfaceWater": null,
          "filmConnection": {
            "__typename": "PlanetFilmsConnection",
            "totalCount": 1,
            "films": [
              {
                "__typename": "Film",
                "title": "Revenge of the Sith",
                "producers": [
                  "Rick McCallum"
                ]
              }
            ]
          }
        },
        {
          "__typename": "Planet",
          "name": "Saleucami",
          "climates": [
            "hot"
          ],
          "surfaceWater": null,
          "filmConnection": {
            "__typename": "PlanetFilmsConnection",
            "totalCount": 1,
            "films": [
              {
                "__typename": "Film",
                "title": "Revenge of the Sith",
                "producers": [
                  "Rick McCallum"
                ]
              }
            ]
          }
        },
        {
          "__typename": "Planet",
          "name": "Stewjon",
          "climates": [
            "temperate"
          ],
          "surfaceWater": null,
          "filmConnection": {
            "__typename": "PlanetFilmsConnection",
            "totalCount": 0,
            "films": []
          }
        },
        {
          "__typename": "Planet",
          "name": "Eriadu",
          "climates": [
            "polluted"
          ],
          "surfaceWater": null,
          "filmConnection": {
            "__typename": "PlanetFilmsConnection",
            "totalCount": 0,
            "films": []
          }
        },
        {
          "__typename": "Planet",
          "name": "Corellia",
          "climates": [
            "temperate"
          ],
          "surfaceWater": 70,
          "filmConnection": {
            "__typename": "PlanetFilmsConnection",
            "totalCount": 0,
            "films": []
          }
        },
        {
          "__typename": "Planet",
          "name": "Rodia",
          "climates": [
            "hot"
          ],
          "surfaceWater": 60,
          "filmConnection": {
            "__typename": "PlanetFilmsConnection",
            "totalCount": 0,
            "films": []
          }
        },
        {
          "__typename": "Planet",
          "name": "Nal Hutta",
          "climates": [
            "temperate"
          ],
          "surfaceWater": null,
          "filmConnection": {
            "__typename": "PlanetFilmsConnection",
            "totalCount": 0,
            "films": []
          }
        },
        {
          "__typename": "Planet",
          "name": "Dantooine",
          "climates": [
            "temperate"
          ],
          "surfaceWater": null,
          "filmConnection": {
            "__typename": "PlanetFilmsConnection",
            "totalCount": 0,
            "films": []
          }
        },
        {
          "__typename": "Planet",
          "name": "Bestine IV",
          "climates": [
            "temperate"
          ],
          "surfaceWater": 98,
          "filmConnection": {
            "__typename": "PlanetFilmsConnection",
            "totalCount": 0,
            "films": []
          }
        },
        {
          "__typename": "Planet",
          "name": "Ord Mantell",
          "climates": [
            "temperate"
          ],
          "surfaceWater": 10,
          "filmConnection": {
            "__typename": "PlanetFilmsConnection",
            "totalCount": 1,
            "films": [
              {
                "__typename": "Film",
                "title": "The Empire Strikes Back",
                "producers": [
                  "Gary Kutz",
                  "Rick McCallum"
                ]
              }
            ]
          }
        },
        {
          "__typename": "Planet",
          "name": "unknown",
          "climates": [
            "unknown"
          ],
          "surfaceWater": null,
          "filmConnection": {
            "__typename": "PlanetFilmsConnection",
            "totalCount": 0,
            "films": []
          }
        },
        {
          "__typename": "Planet",
          "name": "Trandosha",
          "climates": [
            "arid"
          ],
          "surfaceWater": null,
          "filmConnection": {
            "__typename": "PlanetFilmsConnection",
            "totalCount": 0,
            "films": []
          }
        },
        {
          "__typename": "Planet",
          "name": "Socorro",
          "climates": [
            "arid"
          ],
          "surfaceWater": null,
          "filmConnection": {
            "__typename": "PlanetFilmsConnection",
            "totalCount": 0,
            "films": []
          }
        },
        {
          "__typename": "Planet",
          "name": "Mon Cala",
          "climates": [
            "temperate"
          ],
          "surfaceWater": 100,
          "filmConnection": {
            "__typename": "PlanetFilmsConnection",
            "totalCount": 0,
            "films": []
          }
        },
        {
          "__typename": "Planet",
          "name": "Chandrila",
          "climates": [
            "temperate"
          ],
          "surfaceWater": 40,
          "filmConnection": {
            "__typename": "PlanetFilmsConnection",
            "totalCount": 0,
            "films": []
          }
        },
        {
          "__typename": "Planet",
          "name": "Sullust",
          "climates": [
            "superheated"
          ],
          "surfaceWater": 5,
          "filmConnection": {
            "__typename": "PlanetFilmsConnection",
            "totalCount": 0,
            "films": []
          }
        },
        {
          "__typename": "Planet",
          "name": "Toydaria",
          "climates": [
            "temperate"
          ],
          "surfaceWater": null,
          "filmConnection": {
            "__typename": "PlanetFilmsConnection",
            "totalCount": 0,
            "films": []
          }
        },
        {
          "__typename": "Planet",
          "name": "Malastare",
          "climates": [
            "arid",
            "temperate",
            "tropical"
          ],
          "surfaceWater": null,
          "filmConnection": {
            "__typename": "PlanetFilmsConnection",
            "totalCount": 0,
            "films": []
          }
        },
        {
          "__typename": "Planet",
          "name": "Dathomir",
          "climates": [
            "temperate"
          ],
          "surfaceWater": null,
          "filmConnection": {
            "__typename": "PlanetFilmsConnection",
            "totalCount": 0,
            "films": []
          }
        },
        {
          "__typename": "Planet",
          "name": "Ryloth",
          "climates": [
            "temperate",
            "arid",
            "subartic"
          ],
          "surfaceWater": 5,
          "filmConnection": {
            "__typename": "PlanetFilmsConnection",
            "totalCount": 0,
            "films": []
          }
        },
        {
          "__typename": "Planet",
          "name": "Aleen Minor",
          "climates": [
            "unknown"
          ],
          "surfaceWater": null,
          "filmConnection": {
            "__typename": "PlanetFilmsConnection",
            "totalCount": 0,
            "films": []
          }
        },
        {
          "__typename": "Planet",
          "name": "Vulpter",
          "climates": [
            "temperate",
            "artic"
          ],
          "surfaceWater": null,
          "filmConnection": {
            "__typename": "PlanetFilmsConnection",
            "totalCount": 0,
            "films": []
          }
        },
        {
          "__typename": "Planet",
          "name": "Troiken",
          "climates": [
            "unknown"
          ],
          "surfaceWater": null,
          "filmConnection": {
            "__typename": "PlanetFilmsConnection",
            "totalCount": 0,
            "films": []
          }
        },
        {
          "__typename": "Planet",
          "name": "Tund",
          "climates": [
            "unknown"
          ],
          "surfaceWater": null,
          "filmConnection": {
            "__typename": "PlanetFilmsConnection",
            "totalCount": 0,
            "films": []
          }
        },
        {
          "__typename": "Planet",
          "name": "Haruun Kal",
          "climates": [
            "temperate"
          ],
          "surfaceWater": null,
          "filmConnection": {
            "__typename": "PlanetFilmsConnection",
            "totalCount": 0,
            "films": []
          }
        },
        {
          "__typename": "Planet",
          "name": "Cerea",
          "climates": [
            "temperate"
          ],
          "surfaceWater": 20,
          "filmConnection": {
            "__typename": "PlanetFilmsConnection",
            "totalCount": 0,
            "films": []
          }
        },
        {
          "__typename": "Planet",
          "name": "Glee Anselm",
          "climates": [
            "tropical",
            "temperate"
          ],
          "surfaceWater": 80,
          "filmConnection": {
            "__typename": "PlanetFilmsConnection",
            "totalCount": 0,
            "films": []
          }
        },
        {
          "__typename": "Planet",
          "name": "Iridonia",
          "climates": [
            "unknown"
          ],
          "surfaceWater": null,
          "filmConnection": {
            "__typename": "PlanetFilmsConnection",
            "totalCount": 0,
            "films": []
          }
        },
        {
          "__typename": "Planet",
          "name": "Tholoth",
          "climates": [
            "unknown"
          ],
          "surfaceWater": null,
          "filmConnection": {
            "__typename": "PlanetFilmsConnection",
            "totalCount": 0,
            "films": []
          }
        },
        {
          "__typename": "Planet",
          "name": "Iktotch",
          "climates": [
            "arid",
            "rocky",
            "windy"
          ],
          "surfaceWater": null,
          "filmConnection": {
            "__typename": "PlanetFilmsConnection",
            "totalCount": 0,
            "films": []
          }
        },
        {
          "__typename": "Planet",
          "name": "Quermia",
          "climates": [
            "unknown"
          ],
          "surfaceWater": null,
          "filmConnection": {
            "__typename": "PlanetFilmsConnection",
            "totalCount": 0,
            "films": []
          }
        },
        {
          "__typename": "Planet",
          "name": "Dorin",
          "climates": [
            "temperate"
          ],
          "surfaceWater": null,
          "filmConnection": {
            "__typename": "PlanetFilmsConnection",
            "totalCount": 0,
            "films": []
          }
        },
        {
          "__typename": "Planet",
          "name": "Champala",
          "climates": [
            "temperate"
          ],
          "surfaceWater": null,
          "filmConnection": {
            "__typename": "PlanetFilmsConnection",
            "totalCount": 0,
            "films": []
          }
        },
        {
          "__typename": "Planet",
          "name": "Mirial",
          "climates": [
            "unknown"
          ],
          "surfaceWater": null,
          "filmConnection": {
            "__typename": "PlanetFilmsConnection",
            "totalCount": 0,
            "films": []
          }
        },
        {
          "__typename": "Planet",
          "name": "Serenno",
          "climates": [
            "unknown"
          ],
          "surfaceWater": null,
          "filmConnection": {
            "__typename": "PlanetFilmsConnection",
            "totalCount": 0,
            "films": []
          }
        },
        {
          "__typename": "Planet",
          "name": "Concord Dawn",
          "climates": [
            "unknown"
          ],
          "surfaceWater": null,
          "filmConnection": {
            "__typename": "PlanetFilmsConnection",
            "totalCount": 0,
            "films": []
          }
        },
        {
          "__typename": "Planet",
          "name": "Zolan",
          "climates": [
            "unknown"
          ],
          "surfaceWater": null,
          "filmConnection": {
            "__typename": "PlanetFilmsConnection",
            "totalCount": 0,
            "films": []
          }
        },
        {
          "__typename": "Planet",
          "name": "Ojom",
          "climates": [
            "frigid"
          ],
          "surfaceWater": 100,
          "filmConnection": {
            "__typename": "PlanetFilmsConnection",
            "totalCount": 0,
            "films": []
          }
        },
        {
          "__typename": "Planet",
          "name": "Skako",
          "climates": [
            "temperate"
          ],
          "surfaceWater": null,
          "filmConnection": {
            "__typename": "PlanetFilmsConnection",
            "totalCount": 0,
            "films": []
          }
        },
        {
          "__typename": "Planet",
          "name": "Muunilinst",
          "climates": [
            "temperate"
          ],
          "surfaceWater": 25,
          "filmConnection": {
            "__typename": "PlanetFilmsConnection",
            "totalCount": 0,
            "films": []
          }
        },
        {
          "__typename": "Planet",
          "name": "Shili",
          "climates": [
            "temperate"
          ],
          "surfaceWater": null,
          "filmConnection": {
            "__typename": "PlanetFilmsConnection",
            "totalCount": 0,
            "films": []
          }
        },
        {
          "__typename": "Planet",
          "name": "Kalee",
          "climates": [
            "arid",
            "temperate",
            "tropical"
          ],
          "surfaceWater": null,
          "filmConnection": {
            "__typename": "PlanetFilmsConnection",
            "totalCount": 0,
            "films": []
          }
        },
        {
          "__typename": "Planet",
          "name": "Umbara",
          "climates": [
            "unknown"
          ],
          "surfaceWater": null,
          "filmConnection": {
            "__typename": "PlanetFilmsConnection",
            "totalCount": 0,
            "films": []
          }
        }
      ]
    }
  }
}"""

const val HeroTypeDependentAliasedFieldResponse = """
{
  "data": {
    "hero": {
      "__typename": "Droid",
      "property": "Astromech"
    }
  }
}
"""

const val HeroAppearsInResponse = """
{
  "data": {
    "hero": {
      "__typename": "Human",
      "appearsIn": [
        "NEWHOPE",
        "EMPIRE",
        "JEDI"
      ]
    }
  }
}
"""

const val HeroParentTypeDependentFieldHumanResponse = """
{
  "data": {
    "hero": {
      "__typename": "Human",
      "name": "Luke Skywalker",
      "friends": [
        {
          "__typename": "Human",
          "name": "Han Solo",
          "height": 5.905512
        },
        {
          "__typename": "Human",
          "name": "Leia Organa",
          "height": 4.92126
        },
        {
          "__typename": "Droid",
          "name": "C-3PO"
        },
        {
          "__typename": "Droid",
          "name": "R2-D2"
        }
      ]
    }
  }
}
"""

const val HeroAndFriendsConnectionResponse = """
{
  "data": {
    "hero": {
      "__typename": "Droid",
      "name": "R2-D2",
      "birthDate": "1977-05-25",
      "friendsConnection": {
        "__typename": "FriendsConnection",
        "totalCount": 3,
        "edges": [
          {
            "__typename": "FriendsEdge",
            "node": {
              "__typename": "Human",
              "id": "1000"
            }
          },
          {
            "__typename": "FriendsEdge",
            "node": {
              "__typename": "Human",
              "id": "1002"
            }
          },
          {
            "__typename": "FriendsEdge",
            "node": {
              "__typename": "Human",
              "id": "1003"
            }
          }
        ]
      }
    }
  }
}
"""

const val JsonScalarModified = """
{
  "data": {
    "json": {
      "obj": {
        "key2": "value2"
      }
    }
  }
}
"""

const val HeroNameWithIdResponse = """
{
  "data": {
    "hero": {
      "__typename": "Human",
      "id": "1000",
      "name": "SuperMan"
    }
  }
}
"""

const val EpisodeHeroNameResponse = """
{
  "data": {
    "hero": {
      "__typename": "Droid",
      "name": "R2-D2"
    }
  }
}
"""

const val ReviewsEmpireEpisodeResponse = """
{
  "data": {
    "reviews": [
      {
        "__typename": "Review",
        "id": "empireReview1",
        "stars": 1,
        "commentary": "Boring"
      },
      {
        "__typename": "Review",
        "id": "empireReview2",
        "stars": 2,
        "commentary": "So-so"
      },
      {
        "__typename": "Review",
        "id": "empireReview3",
        "stars": 5,
        "commentary": "Amazing"
      }
    ]
  }
}
"""

const val AllPlanetsListOfObjectWithNullObject = """
{
  "data": {
    "allPlanets": {
      "__typename": "PlanetsConnection",
      "planets": [
        {
          "__typename": "Planet",
          "name": "Tatooine",
          "climates": [
            "arid"
          ],
          "surfaceWater": 1,
          "filmConnection": null
        },
        {
          "__typename": "Planet",
          "name": "Alderaan",
          "climates": [
            "temperate"
          ],
          "surfaceWater": 40,
          "filmConnection": {
            "__typename": "PlanetFilmsConnection",
            "totalCount": 2,
            "films": [
              {
                "__typename": "Film",
                "title": "A New Hope",
                "producers": [
                  "Gary Kurtz",
                  "Rick McCallum"
                ]
              },
              {
                "__typename": "Film",
                "title": "Revenge of the Sith",
                "producers": [
                  "Rick McCallum"
                ]
              }
            ]
          }
        }
      ]
    }
  }
}"""

const val HeroAndFriendsNameResponse = """
{
  "data": {
    "hero": {
      "__typename": "Droid",
      "name": "R2-D2",
      "friends": [
        {
          "__typename": "Human",
          "name": "Luke Skywalker"
        },
        {
          "__typename": "Human",
          "name": "Han Solo"
        },
        {
          "__typename": "Human",
          "name": "Leia Organa"
        }
      ]
    }
  }
}
"""

const val HeroNameResponse = """
{
  "data": {
    "hero": {
      "__typename": "Droid",
      "name": "R2-D2"
    }
  },
  "extensions": {
    "cost": {
      "requestedQueryCost": 3,
      "actualQueryCost": 3,
      "throttleStatus": {
        "maximumAvailable": 1000,
        "currentlyAvailable": 997,
        "restoreRate": 50
      }
    }
  }
}
"""

const val HeroAndFriendsNameWithIdsResponse = """
{
  "data": {
    "hero": {
      "__typename": "Droid",
      "id": "2001",
      "name": "R2-D2",
      "friends": [
        {
          "__typename": "Human",
          "id": "1000",
          "name": "Luke Skywalker"
        },
        {
          "__typename": "Human",
          "id": "1002",
          "name": "Han Solo"
        },
        {
          "__typename": "Human",
          "id": "1003",
          "name": "Leia Organa"
        }
      ]
    }
  }
}
"""

const val StarshipByIdResponse = """
{
  "data": {
    "starship": {
      "__typename": "Starship",
      "id": "Starship1",
      "name": "SuperRocket",
      "coordinates": [
        [
          100,
          200
        ],
        [
          300,
          400
        ],
        [
          500,
          600
        ]
      ]
    }
  }
}
"""

const val HeroAppearsInResponseWithNulls = """
{
  "data": {
    "hero": {
      "__typename": "Human",
      "appearsIn": [
        null,
        "NEWHOPE",
        "EMPIRE",
        null,
        "JEDI",
        null
      ]
    }
  }
}
"""

const val SameHeroTwiceResponse = """
{
  "data": {
    "hero": {
      "__typename": "Droid",
      "name": "R2-D2"
    },
    "r2": {
      "__typename": "Droid",
      "appearsIn": [
        "NEWHOPE",
        "EMPIRE",
        "JEDI"
      ]
    }
  }
}
"""

const val HeroAndFriendsNameWithIdsParentOnlyResponse = """
{
  "data": {
    "hero": {
      "__typename": "Droid",
      "id": "2001",
      "name": "R2-D2",
      "friends": [
        {
          "__typename": "Human",
          "name": "Luke Skywalker"
        },
        {
          "__typename": "Human",
          "name": "Han Solo"
        },
        {
          "__typename": "Human",
          "name": "Leia Organa"
        }
      ]
    }
  }
}
"""
