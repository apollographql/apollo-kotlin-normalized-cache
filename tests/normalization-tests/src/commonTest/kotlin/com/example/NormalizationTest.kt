package com.example

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.CustomScalarAdapters
import com.apollographql.apollo.api.json.jsonReader
import com.apollographql.apollo.api.toApolloResponse
import com.apollographql.cache.normalized.ApolloStore
import com.apollographql.cache.normalized.FetchPolicy
import com.apollographql.cache.normalized.allRecords
import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.api.CacheKeyGenerator
import com.apollographql.cache.normalized.api.CacheKeyGeneratorContext
import com.apollographql.cache.normalized.api.CacheKeyResolver
import com.apollographql.cache.normalized.api.CacheResolver
import com.apollographql.cache.normalized.api.FieldPolicyCacheResolver
import com.apollographql.cache.normalized.api.ResolverContext
import com.apollographql.cache.normalized.api.TypePolicyCacheKeyGenerator
import com.apollographql.cache.normalized.apolloStore
import com.apollographql.cache.normalized.fetchPolicy
import com.apollographql.cache.normalized.memory.MemoryCacheFactory
import com.apollographql.cache.normalized.normalizedCache
import com.apollographql.cache.normalized.sql.SqlNormalizedCacheFactory
import com.apollographql.cache.normalized.store
import com.apollographql.cache.normalized.testing.append
import com.apollographql.cache.normalized.testing.runTest
import com.apollographql.mockserver.MockServer
import com.apollographql.mockserver.enqueueString
import com.example.four.CreateUserMutation
import com.example.one.Issue2818Query
import com.example.one.Issue3672Query
import com.example.one.fragment.SectionFragment
import com.example.three.GetBooksByIdsPaginatedNoCursorsQuery
import com.example.three.GetBooksByIdsPaginatedNoCursorsWithFragmentQuery
import com.example.three.GetBooksByIdsPaginatedQuery
import com.example.three.GetBooksByIdsQuery
import com.example.three.type.Book
import com.example.three.type.BookConnection
import com.example.three.type.BookEdge
import com.example.two.GetCountryQuery
import com.example.two.NestedFragmentQuery
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

internal object IdBasedCacheKeyResolver : CacheResolver, CacheKeyGenerator {

  override fun cacheKeyForObject(obj: Map<String, Any?>, context: CacheKeyGeneratorContext) =
    obj["id"]?.toString()?.let(::CacheKey) ?: TypePolicyCacheKeyGenerator.cacheKeyForObject(obj, context)

  override fun resolveField(context: ResolverContext): Any? {
    return FieldPolicyCacheResolver.resolveField(context)
  }
}

class NormalizationTest {
  @Test
  fun issue3672() = runTest {
    val store = ApolloStore(
        normalizedCacheFactory = MemoryCacheFactory(),
        cacheKeyGenerator = IdBasedCacheKeyResolver,
        cacheResolver = IdBasedCacheKeyResolver
    )

    val query = Issue3672Query()

    val data1 =
      Buffer().writeUtf8(nestedResponse).jsonReader().toApolloResponse(operation = query, customScalarAdapters = CustomScalarAdapters.Empty)
          .dataOrThrow()
    store.writeOperation(query, data1)

    val data2 = store.readOperation(query).data
    assertEquals(data2, data1)
  }

  @Test
  fun issue3672_2() = runTest {
    val store = ApolloStore(
        normalizedCacheFactory = MemoryCacheFactory(),
        cacheKeyGenerator = IdBasedCacheKeyResolver,
        cacheResolver = IdBasedCacheKeyResolver
    )

    val query = NestedFragmentQuery()

    val data1 = Buffer().writeUtf8(nestedResponse_list).jsonReader()
        .toApolloResponse(operation = query, customScalarAdapters = CustomScalarAdapters.Empty).dataOrThrow()
    store.writeOperation(query, data1)

    val data2 = store.readOperation(query).data
    assertEquals(data2, data1)
  }

  @Test
  fun issue2818() = runTest {
    val apolloStore = ApolloStore(
        normalizedCacheFactory = MemoryCacheFactory(),
        cacheKeyGenerator = IdBasedCacheKeyResolver,
        cacheResolver = IdBasedCacheKeyResolver
    )

    apolloStore.writeOperation(
        Issue2818Query(),
        Issue2818Query.Data(
            Issue2818Query.Home(
                __typename = "Home",
                sectionA = Issue2818Query.SectionA(
                    name = "section-name",
                ),
                sectionFragment = SectionFragment(
                    sectionA = SectionFragment.SectionA(
                        id = "section-id",
                        imageUrl = "https://...",
                    ),
                ),
            ),
        ),
    )

    val data = apolloStore.readOperation(Issue2818Query()).data!!
    assertEquals("section-name", data.home.sectionA?.name)
    assertEquals("section-id", data.home.sectionFragment.sectionA?.id)
    assertEquals("https://...", data.home.sectionFragment.sectionA?.imageUrl)
  }

  @Test
  // See https://github.com/apollographql/apollo-kotlin/issues/4772
  fun issue4772() = runTest {
    val mockserver = MockServer()
    val apolloClient = ApolloClient.Builder()
        .serverUrl(mockserver.url())
        .normalizedCache(MemoryCacheFactory())
        .build()

    mockserver.enqueueString("""
      {
        "data": {
          "country": {
            "name": "Foo"
          }
        }
      }
    """.trimIndent()
    )
    apolloClient.query(GetCountryQuery("foo")).execute().run {
      check(data?.country?.name == "Foo")
    }
    apolloClient.close()
    mockserver.close()
  }

  @Test
  fun resolveList() = runTest {
    val mockserver = MockServer()
    val apolloClient = ApolloClient.Builder()
        .serverUrl(mockserver.url())
        .store(
            ApolloStore(
                normalizedCacheFactory = MemoryCacheFactory(),
                cacheKeyGenerator = TypePolicyCacheKeyGenerator,
                cacheResolver = object : CacheKeyResolver() {
                  override fun cacheKeyForField(context: ResolverContext): CacheKey? {
                    // Same behavior as FieldPolicyCacheResolver
                    val keyArgsValues = context.field.argumentValues(context.variables) { it.definition.isKey }.values.map { it.toString() }
                    if (keyArgsValues.isNotEmpty()) {
                      return CacheKey(context.field.type.rawType().name, keyArgsValues)
                    }
                    return null
                  }

                  @Suppress("UNCHECKED_CAST")
                  override fun listOfCacheKeysForField(context: ResolverContext): List<CacheKey?>? {
                    return if (context.field.type.rawType() == Book.type) {
                      val bookIds = context.field.argumentValues(context.variables)["bookIds"] as List<String>
                      bookIds.map { CacheKey(Book.type.name, it) }
                    } else {
                      null
                    }
                  }
                }
            )
        )
        .build()

    mockserver.enqueueString("""
      {
        "data": {
          "viewer": {
            "libraries": [
              {
                "__typename": "Library",
                "id": "library-1",
                "books": [
                  {
                    "__typename": "Book",
                    "id": "book-1",
                    "name": "First book",
                    "year": 1991
                  },
                  {
                    "__typename": "Book",
                    "id": "book-2",
                    "name": "Second book",
                    "year": 1992
                  }
                ]
              }
            ]
          }
        }
      }
    """.trimIndent()
    )

    // Fetch from network
    apolloClient.query(GetBooksByIdsQuery(listOf("book-1", "book-2"))).fetchPolicy(FetchPolicy.NetworkOnly).execute()

    // Fetch from the cache
    val fromCache = apolloClient.query(GetBooksByIdsQuery(listOf("book-1"))).fetchPolicy(FetchPolicy.CacheOnly).execute()
    assertEquals("First book", fromCache.data?.viewer?.libraries?.first()?.books?.first()?.name)

    apolloClient.close()
    mockserver.close()
  }

  @Test
  fun resolvePaginatedList() = runTest {
    val mockserver = MockServer()
    val apolloClient = ApolloClient.Builder()
        .serverUrl(mockserver.url())
        .store(
            ApolloStore(
                normalizedCacheFactory = MemoryCacheFactory(),
                cacheKeyGenerator = TypePolicyCacheKeyGenerator,
                cacheResolver = object : CacheResolver {
                  @Suppress("UNCHECKED_CAST")
                  override fun resolveField(context: ResolverContext): Any? {
                    if (context.field.type.rawType() == BookConnection.type) {
                      val bookIds = context.field.argumentValues(context.variables)["bookIds"] as List<String>
                      return mapOf(
                          "edges" to bookIds.map {
                            mapOf(
                                "node" to CacheKey(Book.type.name, it),
                                "__typename" to BookEdge.type.name,
                            )
                          },
                      )
                    }

                    return FieldPolicyCacheResolver.resolveField(context)
                  }
                }
            )
        )
        .build()

    mockserver.enqueueString("""
      {
        "data": {
          "viewer": {
            "libraries": [
              {
                "__typename": "Library",
                "id": "library-1",
                "booksPaginated": {
                  "pageInfo": {
                    "__typename": "PageInfo",
                    "hasNextPage": false,
                    "endCursor": "book-2"
                  },
                  "edges": [
                    {
                      "__typename": "BookEdge",
                      "cursor": "cursor-book-1",
                      "node": {
                        "__typename": "Book",
                        "id": "book-1",
                        "name": "First book",
                        "year": 1991
                      }
                    },
                    {
                      "__typename": "BookEdge",
                      "cursor": "cursor-book-2",
                      "node": {
                        "__typename": "Book",
                        "id": "book-2",
                        "name": "Second book",
                        "year": 1992
                      }
                    }
                  ]
                }
              }
            ]
          }
        }
      }
    """.trimIndent()
    )

    // Fetch from network
    apolloClient.query(GetBooksByIdsPaginatedQuery(listOf("book-1", "book-2"))).fetchPolicy(FetchPolicy.NetworkOnly).execute()
    // println(NormalizedCache.prettifyDump(apolloClient.apolloStore.dump()))

    // Fetch from the cache
    val fromCache1 = apolloClient.query(GetBooksByIdsPaginatedNoCursorsQuery(listOf("book-1"))).fetchPolicy(FetchPolicy.CacheOnly).execute()
    assertEquals("First book", fromCache1.data?.viewer?.libraries?.first()?.booksPaginated?.edges?.first()?.node?.name)

    // Fetch from the cache (with fragment)
    val fromCache2 =
      apolloClient.query(GetBooksByIdsPaginatedNoCursorsWithFragmentQuery(listOf("book-1"))).fetchPolicy(FetchPolicy.CacheOnly).execute()
    assertEquals("First book", fromCache2.data?.viewer?.libraries?.first()?.booksPaginated?.edges?.first()?.bookEdge?.node?.name)

    apolloClient.close()
    mockserver.close()
  }

  @Test
  fun mutationRoot() = runTest {
    val mockserver = MockServer()
    val apolloClient = ApolloClient.Builder()
        .serverUrl(mockserver.url())
        .normalizedCache(SqlNormalizedCacheFactory())
        .build()

    apolloClient.apolloStore.clearAll()
    mockserver.enqueueString(
        // language=JSON
        """
        {
          "data": {
            "createUser": {
              "__typename": "User",
              "id": "user-1",
              "name": "John Doe",
              "projects": [
                {
                  "__typename": "Project",
                  "id": "project-1",
                  "name": "Project 1",
                  "description": "Description 1",
                  "owner": {
                    "__typename": "User",
                    "id": "user-2",
                    "name": "Jane Doe"
                  }
                }
              ]
            }
          }
        }
        """.trimIndent()
    )
    apolloClient.mutation(CreateUserMutation("John")).fetchPolicy(FetchPolicy.NetworkOnly).execute()

    apolloClient.apolloStore.accessCache { normalizedCache ->
      assertContentEquals(
          listOf(
              CacheKey.MUTATION_ROOT,
              CacheKey.MUTATION_ROOT.append("""createUser({"name":"John"})"""),
              CacheKey.MUTATION_ROOT.append("""createUser({"name":"John"})""", "projects", "0"),
              CacheKey.MUTATION_ROOT.append("""createUser({"name":"John"})""", "projects", "0", "owner"),
          ),
          normalizedCache.allRecords().keys,
      )
    }

    apolloClient.close()
    mockserver.close()
  }
}
