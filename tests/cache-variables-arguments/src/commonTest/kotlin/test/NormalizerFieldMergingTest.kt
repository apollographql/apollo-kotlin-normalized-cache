package test

import cache.include.LoneConditionalFieldQuery
import cache.include.TwoConditionalFieldsQuery
import cache.include.TwoFieldsQuery
import cache.include.UserAndCompanyQuery
import cache.include.UsersWithAvatarsQuery
import cache.include.fragment.UserId
import cache.include.fragment.UserName
import com.apollographql.cache.normalized.CacheManager
import com.apollographql.cache.normalized.api.CacheHeaders
import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.api.DefaultCacheKeyGenerator
import com.apollographql.cache.normalized.api.DefaultCacheResolver
import com.apollographql.cache.normalized.api.DefaultFieldKeyGenerator
import com.apollographql.cache.normalized.api.FieldKeyContext
import com.apollographql.cache.normalized.api.FieldKeyGenerator
import com.apollographql.cache.normalized.memory.MemoryCacheFactory
import com.apollographql.cache.normalized.testing.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The normalizer merges the selections that share a response name into a single field, and reuses the
 * selection as is when it is the only one - a field that is already its own merge result. These tests
 * pin down what that reuse must preserve:
 *
 * - the field the normalizer hands to a [FieldKeyGenerator] never carries a condition, whether it was
 *   merged out of several selections or reused as the only one;
 * - a field key is generated once per field of a parent type, not once per object normalized against
 *   it.
 */
class NormalizerFieldMergingTest {
  private data class Call(
      val parentType: String,
      val responseName: String,
      val hasCondition: Boolean,
  )

  /**
   * Records every field it is asked about, and whether that field still carried a condition.
   *
   * The two paths differ here, so the assertion is left to each test rather than made here: see
   * [readingBackAConditionalFieldKeepsItsCondition].
   */
  private class RecordingFieldKeyGenerator : FieldKeyGenerator {
    val calls = mutableListOf<Call>()

    override fun getFieldKey(context: FieldKeyContext): String {
      calls.add(Call(context.parentType, context.field.responseName, context.field.condition.isNotEmpty()))
      return DefaultFieldKeyGenerator.getFieldKey(context)
    }
  }

  /**
   * Takes a snapshot of what was recorded and clears the log, so a later read does not show up in the
   * assertions about normalizing.
   */
  private fun RecordingFieldKeyGenerator.drain(): List<Call> = calls.toList().also { calls.clear() }

  private fun List<Call>.names() = map { it.parentType to it.responseName }

  private fun List<Call>.assertNoConditions() {
    val conditional = filter { it.hasCondition }
    assertTrue(
        conditional.isEmpty(),
        "Normalized fields were passed to the FieldKeyGenerator with a condition: " +
            conditional.joinToString { "${it.parentType}.${it.responseName}" },
    )
  }

  private fun cacheManager(fieldKeyGenerator: FieldKeyGenerator) = CacheManager(
      normalizedCacheFactory = MemoryCacheFactory(),
      cacheKeyGenerator = DefaultCacheKeyGenerator,
      cacheResolver = DefaultCacheResolver,
      fieldKeyGenerator = fieldKeyGenerator,
  )

  /**
   * `user` is selected once and conditionally, so the normalizer reuses that selection instead of
   * building a merged field from it. The condition must be cleared all the same.
   */
  @Test
  fun loneConditionalFieldIsNormalizedWithoutItsCondition() = runTest {
    val generator = RecordingFieldKeyGenerator()
    val cacheManager = cacheManager(generator)
    val operation = LoneConditionalFieldQuery(details = true)

    cacheManager.writeOperation(
        operation,
        LoneConditionalFieldQuery.Data(user = LoneConditionalFieldQuery.User(id = "42", name = "John")),
    )

    val normalizing = generator.drain()
    normalizing.assertNoConditions()
    assertTrue(normalizing.names().contains("Query" to "user"))

    val data = cacheManager.readOperation(operation).data!!
    assertEquals("42", data.user?.id)
    assertEquals("John", data.user?.name)
  }

  @Test
  fun loneConditionalFieldIsNotStoredWhenSkipped() = runTest {
    val cacheManager = cacheManager(RecordingFieldKeyGenerator())

    cacheManager.writeOperation(LoneConditionalFieldQuery(details = false), LoneConditionalFieldQuery.Data(user = null))

    // The field was skipped, so nothing was stored for it - not even a null.
    val record = cacheManager.accessCache { it.loadRecord(CacheKey.QUERY_ROOT, CacheHeaders.NONE) }
    assertEquals(emptyMap(), record?.fields ?: emptyMap())
  }

  @Test
  fun twoSelectionsSharingAConditionAreMerged() = runTest {
    val generator = RecordingFieldKeyGenerator()
    val cacheManager = cacheManager(generator)
    val operation = TwoConditionalFieldsQuery(details = true)

    cacheManager.writeOperation(
        operation,
        TwoConditionalFieldsQuery.Data(user = TwoConditionalFieldsQuery.User(id = "42", name = "John")),
    )

    val normalizing = generator.drain()
    normalizing.assertNoConditions()
    // One merged field, so `user` is asked about once even though it was selected twice.
    assertEquals(1, normalizing.names().count { it == "Query" to "user" })

    val data = cacheManager.readOperation(operation).data!!
    assertEquals("42", data.user?.id)
    assertEquals("John", data.user?.name)
  }

  /**
   * `user` is selected twice, once unconditionally and once with a condition, so the two selections
   * merge into one field.
   */
  @Test
  fun selectionsWithDifferentConditionsAreMerged() = runTest {
    val generator = RecordingFieldKeyGenerator()
    val cacheManager = cacheManager(generator)
    val operation = TwoFieldsQuery(details = true)

    cacheManager.writeOperation(
        operation,
        TwoFieldsQuery.Data(
            __typename = "Query",
            userId = UserId(UserId.User("42")),
            userName = UserName(user = UserName.User("John")),
        ),
    )

    val normalizing = generator.drain()
    normalizing.assertNoConditions()
    assertEquals(1, normalizing.names().count { it == "Query" to "user" })

    val data = cacheManager.readOperation(operation).data!!
    assertEquals("42", data.userId.user.id)
    assertEquals("John", data.userName.user?.name)
  }

  /**
   * Same query with the condition evaluating the other way: only the unconditional selection of `user`
   * is left, so the merged field is built from one selection.
   *
   * There is no reading back here: the two fragments share the `user` response name, and the generated
   * adapters do not evaluate conditions, so the one belonging to the skipped selection parses whatever
   * the other one left under that name and asks for a `name` that was never selected. That is the shape
   * of the generated operation - a network response would run into it too - not something the cache
   * decides. See https://github.com/apollographql/apollo-kotlin/issues/6901.
   */
  @Test
  fun aSkippedSelectionLeavesTheOtherOneToMerge() = runTest {
    val generator = RecordingFieldKeyGenerator()
    val cacheManager = cacheManager(generator)

    cacheManager.writeOperation(
        TwoFieldsQuery(details = false),
        TwoFieldsQuery.Data(
            __typename = "Query",
            userId = UserId(UserId.User("42")),
            userName = UserName(user = null),
        ),
    )

    val normalizing = generator.drain()
    normalizing.assertNoConditions()
    assertEquals(1, normalizing.names().count { it == "Query" to "user" })
    // `name` was not selected, so no key was generated for it.
    assertEquals(0, normalizing.names().count { it == "User" to "name" })
  }

  /**
   * The reader merges the selections sharing a response name *and* a condition, and keeps that
   * condition on the merged field - unlike the normalizer, which clears it. So a [FieldKeyGenerator]
   * sees a conditional field when reading and an unconditional one when writing.
   *
   * That asymmetry is harmless for [DefaultFieldKeyGenerator], which keys on the response name and the
   * arguments only, and it is not something these changes introduced. It is pinned here so that a
   * change to either path is a deliberate one.
   */
  @Test
  fun readingBackAConditionalFieldKeepsItsCondition() = runTest {
    val generator = RecordingFieldKeyGenerator()
    val cacheManager = cacheManager(generator)
    val operation = LoneConditionalFieldQuery(details = true)

    cacheManager.writeOperation(
        operation,
        LoneConditionalFieldQuery.Data(user = LoneConditionalFieldQuery.User(id = "42", name = "John")),
    )
    generator.drain().assertNoConditions()

    cacheManager.readOperation(operation)

    val reading = generator.drain()
    assertEquals(
        listOf(Call("Query", "user", hasCondition = true)),
        reading.filter { it.responseName == "user" },
    )
  }

  /**
   * The same fields are normalized again for every object of a list, and generating a field key encodes
   * the field's arguments to JSON. The number of keys generated must therefore not grow with the list.
   */
  @Test
  fun fieldKeysAreGeneratedOncePerFieldRatherThanPerObject() = runTest {
    fun users(count: Int) = List(count) { index ->
      UsersWithAvatarsQuery.User(id = index.toString(), avatar = "avatar$index")
    }

    val oneUser = RecordingFieldKeyGenerator()
    cacheManager(oneUser).writeOperation(UsersWithAvatarsQuery(size = 64), UsersWithAvatarsQuery.Data(users = users(1)))

    val manyUsers = RecordingFieldKeyGenerator()
    cacheManager(manyUsers).writeOperation(UsersWithAvatarsQuery(size = 64), UsersWithAvatarsQuery.Data(users = users(10)))

    assertEquals(
        oneUser.calls.names(),
        manyUsers.calls.names(),
        "Field keys were generated per object: normalizing 10 users asked for ${manyUsers.calls.size} field keys " +
            "where one user asked for ${oneUser.calls.size}",
    )
    assertEquals(listOf("Query" to "users", "User" to "id", "User" to "avatar"), oneUser.calls.names())
  }

  @Test
  fun fieldKeysCarryTheirArguments() = runTest {
    val cacheManager = cacheManager(DefaultFieldKeyGenerator)
    val operation = UsersWithAvatarsQuery(size = 64)

    cacheManager.writeOperation(
        operation,
        UsersWithAvatarsQuery.Data(users = listOf(UsersWithAvatarsQuery.User(id = "1", avatar = "avatar1"))),
    )

    val data = cacheManager.readOperation(operation).data!!
    assertEquals("avatar1", data.users.single().avatar)

    // A different argument is a different field key, so it is a cache miss rather than a stale hit.
    val otherSize = cacheManager.readOperation(UsersWithAvatarsQuery(size = 128))
    assertEquals(null, otherSize.data?.users?.single()?.avatar)
  }

  /**
   * Field keys are memoized per parent type, so two types that select the same field name must not
   * share a key.
   */
  @Test
  fun fieldKeysAreNotSharedAcrossParentTypes() = runTest {
    val generator = RecordingFieldKeyGenerator()
    val cacheManager = cacheManager(generator)
    val operation = UserAndCompanyQuery()

    cacheManager.writeOperation(
        operation,
        UserAndCompanyQuery.Data(
            user = UserAndCompanyQuery.User(id = "42", name = "John"),
            company = UserAndCompanyQuery.Company(id = "1", name = "Acme"),
        ),
    )

    val normalizing = generator.drain().names()
    assertEquals(1, normalizing.count { it == "User" to "id" })
    assertEquals(1, normalizing.count { it == "Company" to "id" })

    val data = cacheManager.readOperation(operation).data!!
    assertEquals("John", data.user.name)
    assertEquals("Acme", data.company.name)
  }
}
