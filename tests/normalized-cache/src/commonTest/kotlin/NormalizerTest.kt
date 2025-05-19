package test

import com.apollographql.apollo.api.Operation
import com.apollographql.apollo.api.toApolloResponse
import com.apollographql.cache.normalized.api.CacheHeaders
import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.api.DefaultMaxAgeProvider
import com.apollographql.cache.normalized.api.DefaultRecordMerger
import com.apollographql.cache.normalized.api.IdCacheKeyGenerator
import com.apollographql.cache.normalized.api.MaxAgeContext
import com.apollographql.cache.normalized.api.MaxAgeProvider
import com.apollographql.cache.normalized.api.NormalizedCache
import com.apollographql.cache.normalized.api.Record
import com.apollographql.cache.normalized.internal.normalized
import com.apollographql.cache.normalized.memory.MemoryCacheFactory
import com.apollographql.cache.normalized.testing.append
import httpcache.AllPlanetsQuery
import normalizer.EpisodeHeroNameQuery
import normalizer.HeroAndFriendsConnectionQuery
import normalizer.HeroAndFriendsNamesQuery
import normalizer.HeroAndFriendsNamesWithIDForParentOnlyQuery
import normalizer.HeroAndFriendsNamesWithIDsQuery
import normalizer.HeroAppearsInQuery
import normalizer.HeroNameQuery
import normalizer.HeroParentTypeDependentFieldQuery
import normalizer.HeroTypeDependentAliasedFieldQuery
import normalizer.SameHeroTwiceQuery
import normalizer.type.Episode
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * Tests for the normalization without an instance of [com.apollographql.apollo.ApolloClient]
 */
class NormalizerTest {
  private lateinit var normalizedCache: NormalizedCache

  @BeforeTest
  fun setUp() {
    normalizedCache = MemoryCacheFactory().create()
  }

  @Test
  @Throws(Exception::class)
  fun testHeroName() {
    val records = records(HeroNameQuery(), "HeroNameResponse.json")
    val record = records.get(CacheKey.QUERY_ROOT)
    val reference = record!!["hero"] as CacheKey?
    assertEquals(reference, CacheKey("hero"))
    val heroRecord = records.get(reference!!)
    assertEquals(heroRecord!!["name"], "R2-D2")
  }

  @Test
  @Throws(Exception::class)
  fun testMergeNull() {
    val record = Record(
        key = CacheKey("Key"),
        fields = mapOf("field1" to "value1"),
    )
    normalizedCache.merge(listOf(record), CacheHeaders.NONE, DefaultRecordMerger)

    val newRecord = Record(
        key = CacheKey("Key"),
        fields = mapOf("field2" to null),
    )

    normalizedCache.merge(listOf(newRecord), CacheHeaders.NONE, DefaultRecordMerger)
    val finalRecord = normalizedCache.loadRecord(record.key, CacheHeaders.NONE)
    assertTrue(finalRecord!!.containsKey("field2"))
    normalizedCache.remove(record.key, false)
  }

  @Test
  @Throws(Exception::class)
  fun testHeroNameWithVariable() {
    val records = records(EpisodeHeroNameQuery(Episode.JEDI), "EpisodeHeroNameResponse.json")
    val record = records.get(CacheKey.QUERY_ROOT)
    val reference = record!![TEST_FIELD_KEY_JEDI] as CacheKey?
    assertEquals(reference, CacheKey(TEST_FIELD_KEY_JEDI))
    val heroRecord = records.get(reference!!)
    assertEquals(heroRecord!!["name"], "R2-D2")
  }

  @Test
  @Throws(Exception::class)
  fun testHeroAppearsInQuery() {
    val records = records(HeroAppearsInQuery(), "HeroAppearsInResponse.json")

    val rootRecord = records.get(CacheKey.QUERY_ROOT)!!

    val heroReference = rootRecord["hero"] as CacheKey?
    assertEquals(heroReference, CacheKey("hero"))

    val hero = records.get(heroReference!!)
    assertEquals(hero?.get("appearsIn"), listOf("NEWHOPE", "EMPIRE", "JEDI"))
  }

  @Test
  @Throws(Exception::class)
  fun testHeroAndFriendsNamesQueryWithoutIDs() {
    val records = records(HeroAndFriendsNamesQuery(Episode.JEDI), "HeroAndFriendsNameResponse.json")
    val record = records.get(CacheKey.QUERY_ROOT)
    val heroReference = record!![TEST_FIELD_KEY_JEDI] as CacheKey?
    assertEquals(heroReference, CacheKey(TEST_FIELD_KEY_JEDI))
    val heroRecord = records.get(heroReference!!)
    assertEquals(heroRecord!!["name"], "R2-D2")
    assertEquals(
        listOf(
            CacheKey(TEST_FIELD_KEY_JEDI).append("friends", "0"),
            CacheKey(TEST_FIELD_KEY_JEDI).append("friends", "1"),
            CacheKey(TEST_FIELD_KEY_JEDI).append("friends", "2"),
        ),
        heroRecord["friends"]
    )
    val luke = records.get(CacheKey(TEST_FIELD_KEY_JEDI).append("friends", "0"))
    assertEquals(luke!!["name"], "Luke Skywalker")
  }

  @Test
  @Throws(Exception::class)
  fun testHeroAndFriendsNamesQueryWithIDs() {
    val records = records(HeroAndFriendsNamesWithIDsQuery(Episode.JEDI), "HeroAndFriendsNameWithIdsResponse.json")
    val record = records.get(CacheKey.QUERY_ROOT)
    val heroReference = record!![TEST_FIELD_KEY_JEDI] as CacheKey?
    assertEquals(CacheKey("Droid:2001"), heroReference)
    val heroRecord = records.get(heroReference!!)
    assertEquals(heroRecord!!["name"], "R2-D2")
    assertEquals(
        listOf(
            CacheKey("Human:1000"),
            CacheKey("Human:1002"),
            CacheKey("Human:1003")
        ),
        heroRecord["friends"]
    )
    val luke = records.get(CacheKey("Human:1000"))
    assertEquals(luke!!["name"], "Luke Skywalker")
  }

  @Test
  @Throws(Exception::class)
  fun testHeroAndFriendsNamesWithIDForParentOnly() {
    val records = records(HeroAndFriendsNamesWithIDForParentOnlyQuery(Episode.JEDI), "HeroAndFriendsNameWithIdsParentOnlyResponse.json")
    val record = records[CacheKey.QUERY_ROOT]
    val heroReference = record!![TEST_FIELD_KEY_JEDI] as CacheKey?
    assertEquals(CacheKey("Droid:2001"), heroReference)
    val heroRecord = records.get(heroReference!!)
    assertEquals(heroRecord!!["name"], "R2-D2")
    assertEquals(
        listOf(
            CacheKey("Droid:2001").append("friends", "0"),
            CacheKey("Droid:2001").append("friends", "1"),
            CacheKey("Droid:2001").append("friends", "2")
        ),
        heroRecord["friends"]
    )
    val luke = records.get(CacheKey("Droid:2001").append("friends", "0"))
    assertEquals(luke!!["name"], "Luke Skywalker")
  }

  @Test
  @Throws(Exception::class)
  fun testSameHeroTwiceQuery() {
    val records = records(SameHeroTwiceQuery(), "SameHeroTwiceResponse.json")
    val record = records.get(CacheKey.QUERY_ROOT)
    val heroReference = record!!["hero"] as CacheKey?
    val hero = records.get(heroReference!!)

    assertEquals(hero!!["name"], "R2-D2")
    assertEquals(hero["appearsIn"], listOf("NEWHOPE", "EMPIRE", "JEDI"))
  }

  @Test
  @Throws(Exception::class)
  fun testHeroTypeDependentAliasedFieldQueryDroid() {
    val records = records(HeroTypeDependentAliasedFieldQuery(Episode.JEDI), "HeroTypeDependentAliasedFieldResponse.json")
    val record = records.get(CacheKey.QUERY_ROOT)
    val heroReference = record!![TEST_FIELD_KEY_JEDI] as CacheKey?
    val hero = records.get(heroReference!!)
    assertEquals(hero!!["primaryFunction"], "Astromech")
    assertEquals(hero["__typename"], "Droid")
  }

  @Test
  @Throws(Exception::class)
  fun testHeroTypeDependentAliasedFieldQueryHuman() {
    val records = records(HeroTypeDependentAliasedFieldQuery(Episode.EMPIRE), "HeroTypeDependentAliasedFieldResponseHuman.json")
    val record = records.get(CacheKey.QUERY_ROOT)
    val heroReference = record!![TEST_FIELD_KEY_EMPIRE] as CacheKey?
    val hero = records.get(heroReference!!)
    assertEquals(hero!!["homePlanet"], "Tatooine")
    assertEquals(hero["__typename"], "Human")
  }

  @Test
  @Throws(Exception::class)
  fun testHeroParentTypeDependentAliasedFieldQueryHuman() {
    val records = records(HeroTypeDependentAliasedFieldQuery(Episode.EMPIRE), "HeroTypeDependentAliasedFieldResponseHuman.json")
    val record = records.get(CacheKey.QUERY_ROOT)
    val heroReference = record!![TEST_FIELD_KEY_EMPIRE] as CacheKey?
    val hero = records.get(heroReference!!)
    assertEquals(hero!!["homePlanet"], "Tatooine")
    assertEquals(hero["__typename"], "Human")
  }

  @Test
  @Throws(Exception::class)
  fun testHeroParentTypeDependentFieldDroid() {
    val records = records(HeroParentTypeDependentFieldQuery(Episode.JEDI), "HeroParentTypeDependentFieldDroidResponse.json")
    val lukeRecord = records.get(CacheKey(TEST_FIELD_KEY_JEDI).append("friends", "0"))
    assertEquals(lukeRecord!!["name"], "Luke Skywalker")
    assertEquals(lukeRecord["height({\"unit\":\"METER\"})"], 1.72)


    val friends = records[CacheKey(TEST_FIELD_KEY_JEDI)]!!["friends"]

    assertIs<List<Any>>(friends)
    assertEquals(friends[0], CacheKey(TEST_FIELD_KEY_JEDI).append("friends", "0"))
    assertEquals(friends[1], CacheKey(TEST_FIELD_KEY_JEDI).append("friends", "1"))
    assertEquals(friends[2], CacheKey(TEST_FIELD_KEY_JEDI).append("friends", "2"))
  }

  @Test
  fun list_of_objects_with_null_object() {
    val records = records(AllPlanetsQuery(), "AllPlanetsListOfObjectWithNullObject.json")
    val fieldKey = CacheKey("allPlanets({\"first\":300})")

    var record: Record? = records[fieldKey.append("planets", "0")]
    assertTrue(record?.get("filmConnection") == null)
    record = records.get(fieldKey.append("planets", "0", "filmConnection"))
    assertTrue(record == null)
    record = records.get(fieldKey.append("planets", "1", "filmConnection"))
    assertTrue(record != null)
  }


  @Test
  @Throws(Exception::class)
  fun testHeroParentTypeDependentFieldHuman() {
    val records = records(HeroParentTypeDependentFieldQuery(Episode.EMPIRE), "HeroParentTypeDependentFieldHumanResponse.json")

    val lukeRecord = records.get(CacheKey(TEST_FIELD_KEY_EMPIRE).append("friends", "0"))
    assertEquals(lukeRecord!!["name"], "Han Solo")
    assertEquals(lukeRecord["height({\"unit\":\"FOOT\"})"], 5.905512)
  }

  @Test
  fun testDoNotStore() {
    val maxAgeProvider = object : MaxAgeProvider {
      override fun getMaxAge(maxAgeContext: MaxAgeContext): Duration {
        val field = maxAgeContext.fieldPath.last()
        val parentField = maxAgeContext.fieldPath.getOrNull(maxAgeContext.fieldPath.lastIndex - 1)
        // Don't store fields of type FriendsConnection nor fields inside FriendsConnection
        if (field.type.name == "FriendsConnection" || parentField?.type?.name == "FriendsConnection") {
          return Duration.ZERO
        }
        return Duration.INFINITE
      }
    }
    val records =
      records(HeroAndFriendsConnectionQuery(Episode.EMPIRE), "HeroAndFriendsConnectionResponse.json", maxAgeProvider = maxAgeProvider)
    assertTrue(records[CacheKey("hero({\"episode\":\"EMPIRE\"})")]!!["friendsConnection"] == null)
    assertTrue(records[CacheKey("hero({\"episode\":\"EMPIRE\"})").append("friendsConnection")]!!.isEmpty())
  }

  companion object {
    internal fun <D : Operation.Data> records(
        operation: Operation<D>,
        name: String,
        maxAgeProvider: MaxAgeProvider = DefaultMaxAgeProvider,
    ): Map<CacheKey, Record> {
      val response = testFixtureToJsonReader(name).toApolloResponse(operation)
      return response.data!!.normalized(operation, cacheKeyGenerator = IdCacheKeyGenerator(), maxAgeProvider = maxAgeProvider)
    }

    private const val TEST_FIELD_KEY_JEDI = "hero({\"episode\":\"JEDI\"})"
    const val TEST_FIELD_KEY_EMPIRE = "hero({\"episode\":\"EMPIRE\"})"
  }
}
