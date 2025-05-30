package fixtures

const val HeroHumanOrDroid = """
{
  "data": {
    "hero": {
      "__typename": "Droid",
      "name": "R2-D2",
      "primaryFunction": "Astromech"
    }
  }
}
"""

const val HeroParentTypeDependentField = """
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

const val HeroAndFriendsNamesWithIDs = """
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

const val MergedFieldWithSameShape_Human = """
{
  "data": {
    "hero": {
      "__typename": "Human",
      "property": "Tatooine"
    }
  }
}
"""

const val HeroAndFriendsWithTypename = """
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

const val MergedFieldWithSameShape_Droid = """
{
  "data": {
    "hero": {
      "__typename": "Droid",
      "property": "Astromech"
    }
  }
}
"""

