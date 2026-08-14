package test

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Error
import com.apollographql.cache.normalized.FetchPolicy
import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.api.FieldPolicyCacheResolver
import com.apollographql.cache.normalized.api.TypePolicyCacheKeyGenerator
import com.apollographql.cache.normalized.apolloStore
import com.apollographql.cache.normalized.fetchPolicy
import com.apollographql.cache.normalized.fetchPolicyInterceptor
import com.apollographql.cache.normalized.memory.MemoryCacheFactory
import com.apollographql.cache.normalized.normalizedCache
import com.apollographql.cache.normalized.testing.assertErrorsEquals
import com.apollographql.cache.normalized.testing.keyToString
import com.apollographql.cache.normalized.testing.runTest
import com.apollographql.mockserver.MockServer
import com.apollographql.mockserver.enqueueString
import okio.use
import test.cache.Cache
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The reader keys the objects it reads by their response path, and the paths of one response share
 * prefixes, so they are interned: two paths with the same segments are the same object, and appending a
 * segment to a path is a lookup rather than a copy.
 *
 * A list is where that can go wrong, because its elements differ only in their last segment. These
 * tests read lists of lists, so every object's path shares a prefix with its siblings' - at both
 * levels - and check that each of them is read as itself and that a cache miss is reported at its own
 * path.
 */
class ResponsePathTest {
  private lateinit var mockServer: MockServer

  private fun setUp() {
    mockServer = MockServer()
  }

  private fun tearDown() {
    mockServer.close()
  }

  @Test
  fun objectsOfNestedListsAreReadAtTheirOwnPath() = runTest(before = { setUp() }, after = { tearDown() }) {
    mockServer.enqueueString(threeProjectsResponse)
    ApolloClient.Builder()
        .serverUrl(mockServer.url())
        .normalizedCache(
            MemoryCacheFactory(),
            cacheKeyGenerator = TypePolicyCacheKeyGenerator(Cache.typePolicies),
            cacheResolver = FieldPolicyCacheResolver(Cache.fieldPolicies),
        )
        .build()
        .use { apolloClient ->
          val networkResult = apolloClient.query(MeWithBestFriendQuery())
              .fetchPolicy(FetchPolicy.NetworkOnly)
              .execute()
          assertEquals(threeProjectsData, networkResult.data)

          // Read back from the cache: every project and every user has to come back where it was.
          val cacheResult = apolloClient.query(MeWithBestFriendQuery())
              .fetchPolicyInterceptor(PartialCacheOnlyInterceptor)
              .execute()
          assertEquals(threeProjectsData, cacheResult.data)
          assertNull(cacheResult.errors)
        }
  }

  /**
   * Both objects that go missing are at a non-zero index, so a path built from a sibling's - or from the
   * first one read at that level - would report the wrong one. `lead` is nullable, so the rest of the
   * response survives and can be checked too.
   */
  @Test
  fun aCacheMissInsideAListIsReportedAtItsOwnIndex() = runTest(before = { setUp() }, after = { tearDown() }) {
    withPrimedCache { apolloClient ->
      // The leads of the second and third projects.
      apolloClient.apolloStore.remove(CacheKey("User:11"))
      apolloClient.apolloStore.remove(CacheKey("User:12"))

      val cacheResult = apolloClient.query(MeWithBestFriendQuery())
          .fetchPolicyInterceptor(PartialCacheOnlyInterceptor)
          .execute()

      assertErrorsEquals(
          listOf(
              Error.Builder("Object '${CacheKey("User:11").keyToString()}' not found in the cache")
                  .path(listOf("me", "projects", 1, "lead"))
                  .build(),
              Error.Builder("Object '${CacheKey("User:12").keyToString()}' not found in the cache")
                  .path(listOf("me", "projects", 2, "lead"))
                  .build(),
          ),
          cacheResult.errors,
      )

      // Everything else is untouched, each project's users included.
      val projects = cacheResult.data!!.me!!.projects
      assertEquals("Lead0", projects[0].lead!!.firstName)
      assertNull(projects[1].lead)
      assertNull(projects[2].lead)
      assertEquals(
          listOf(
              listOf("User100", "User101"),
              listOf("User110", "User111"),
              listOf("User120", "User121"),
          ),
          projects.map { project -> project.users.map { it.firstName } },
      )
    }
  }

  /**
   * The missing object is the last element of the inner list, so its path ends on a non-zero index and
   * is the deepest of the response.
   *
   * `Project.users` is a non-null list of non-null users inside a non-null list of projects, itself on a
   * non-null `me`, so the error propagates all the way to the root and there is no data left to check -
   * only the path it is reported at, which is the point here.
   */
  @Test
  fun aCacheMissAtTheDeepestPathIsReportedAtItsOwnIndex() = runTest(before = { setUp() }, after = { tearDown() }) {
    withPrimedCache { apolloClient ->
      apolloClient.apolloStore.remove(CacheKey("User:121"))

      val cacheResult = apolloClient.query(MeWithBestFriendQuery())
          .fetchPolicyInterceptor(PartialCacheOnlyInterceptor)
          .execute()

      assertErrorsEquals(
          listOf(
              Error.Builder("Object '${CacheKey("User:121").keyToString()}' not found in the cache")
                  .path(listOf("me", "projects", 2, "users", 1))
                  .build(),
          ),
          cacheResult.errors,
      )
      assertNull(cacheResult.data)
    }
  }

  private suspend fun withPrimedCache(block: suspend (ApolloClient) -> Unit) {
    mockServer.enqueueString(threeProjectsResponse)
    ApolloClient.Builder()
        .serverUrl(mockServer.url())
        .normalizedCache(
            MemoryCacheFactory(),
            cacheKeyGenerator = TypePolicyCacheKeyGenerator(Cache.typePolicies),
            cacheResolver = FieldPolicyCacheResolver(Cache.fieldPolicies),
        )
        .build()
        .use { apolloClient ->
          apolloClient.query(MeWithBestFriendQuery()).fetchPolicy(FetchPolicy.NetworkOnly).execute()
          block(apolloClient)
        }
  }

  private fun user(id: String, firstName: String) = MeWithBestFriendQuery.User(
      __typename = "User",
      id = id,
      firstName = firstName,
      lastName = "Doe",
  )

  private fun lead(id: String, firstName: String) = MeWithBestFriendQuery.Lead(
      __typename = "User",
      id = id,
      firstName = firstName,
      lastName = "Doe",
  )

  private val threeProjectsData = MeWithBestFriendQuery.Data(
      MeWithBestFriendQuery.Me(
          __typename = "User",
          id = "1",
          firstName = "John",
          lastName = "Smith",
          bestFriend = MeWithBestFriendQuery.BestFriend(
              __typename = "User",
              id = "2",
              firstName = "Jane",
              lastName = "Doe",
          ),
          projects = listOf(
              MeWithBestFriendQuery.Project(
                  __typename = "Project",
                  lead = lead("10", "Lead0"),
                  users = listOf(user("100", "User100"), user("101", "User101")),
              ),
              MeWithBestFriendQuery.Project(
                  __typename = "Project",
                  lead = lead("11", "Lead1"),
                  users = listOf(user("110", "User110"), user("111", "User111")),
              ),
              MeWithBestFriendQuery.Project(
                  __typename = "Project",
                  lead = lead("12", "Lead2"),
                  users = listOf(user("120", "User120"), user("121", "User121")),
              ),
          ),
      ),
  )
}

private fun userJson(id: String, firstName: String) =
  """{"__typename": "User", "id": "$id", "firstName": "$firstName", "lastName": "Doe"}"""

private fun projectJson(leadId: String, leadName: String, users: List<Pair<String, String>>) =
  """
  {
    "__typename": "Project",
    "lead": ${userJson(leadId, leadName)},
    "users": [${users.joinToString(", ") { userJson(it.first, it.second) }}]
  }
  """

private val threeProjectsResponse =
  // language=JSON
  """
  {
    "data": {
      "me": {
        "__typename": "User",
        "id": "1",
        "firstName": "John",
        "lastName": "Smith",
        "bestFriend": ${userJson("2", "Jane")},
        "projects": [
          ${projectJson("10", "Lead0", listOf("100" to "User100", "101" to "User101"))},
          ${projectJson("11", "Lead1", listOf("110" to "User110", "111" to "User111"))},
          ${projectJson("12", "Lead2", listOf("120" to "User120", "121" to "User121"))}
        ]
      }
    }
  }
  """
