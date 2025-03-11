package pagination

import com.apollographql.apollo.api.Optional
import com.apollographql.apollo.testing.internal.runTest
import com.apollographql.cache.normalized.ApolloStore
import com.apollographql.cache.normalized.api.ConnectionMetadataGenerator
import com.apollographql.cache.normalized.api.ConnectionRecordMerger
import com.apollographql.cache.normalized.api.FieldPolicyCacheResolver
import com.apollographql.cache.normalized.api.NormalizedCacheFactory
import com.apollographql.cache.normalized.api.TypePolicyCacheKeyGenerator
import com.apollographql.cache.normalized.memory.MemoryCacheFactory
import com.apollographql.cache.normalized.sql.SqlNormalizedCacheFactory
import pagination.connectionWithNodes.UsersQuery
import pagination.connectionWithNodes.pagination.Pagination
import pagination.connectionWithNodes.type.buildPageInfo
import pagination.connectionWithNodes.type.buildUser
import pagination.connectionWithNodes.type.buildUserConnection
import kotlin.test.Test
import kotlin.test.assertEquals

class ConnectionWithNodesPaginationTest {
  @Test
  fun memoryCache() {
    test(MemoryCacheFactory())
  }

  @Test
  fun sqlCache() {
    test(SqlNormalizedCacheFactory())
  }

  @Test
  fun chainedCache() {
    test(MemoryCacheFactory().chain(SqlNormalizedCacheFactory()))
  }

  private fun test(cacheFactory: NormalizedCacheFactory) = runTest {
    val apolloStore = ApolloStore(
        normalizedCacheFactory = cacheFactory,
        cacheKeyGenerator = TypePolicyCacheKeyGenerator,
        metadataGenerator = ConnectionMetadataGenerator(Pagination.connectionTypes),
        cacheResolver = FieldPolicyCacheResolver,
        recordMerger = ConnectionRecordMerger
    )
    apolloStore.clearAll()

    // First page
    val query1 = UsersQuery(first = Optional.Present(2))
    val data1 = UsersQuery.Data {
      users = buildUserConnection {
        pageInfo = buildPageInfo {
          startCursor = "xx42"
          endCursor = "xx43"
        }
        nodes = listOf(
            buildUser {
              id = "42"
            },
            buildUser {
              id = "43"
            },
        )
      }
    }
    apolloStore.writeOperation(query1, data1)
    var dataFromStore = apolloStore.readOperation(query1).data
    assertEquals(data1, dataFromStore)

    // Page after
    val query2 = UsersQuery(first = Optional.Present(2), after = Optional.Present("xx43"))
    val data2 = UsersQuery.Data {
      users = buildUserConnection {
        pageInfo = buildPageInfo {
          startCursor = "xx44"
          endCursor = "xx45"
        }
        nodes = listOf(
            buildUser {
              id = "44"
            },
            buildUser {
              id = "45"
            },
        )
      }
    }
    apolloStore.writeOperation(query2, data2)
    dataFromStore = apolloStore.readOperation(query1).data
    var expectedData = UsersQuery.Data {
      users = buildUserConnection {
        pageInfo = buildPageInfo {
          startCursor = "xx42"
          endCursor = "xx45"
        }
        nodes = listOf(
            buildUser {
              id = "42"
            },
            buildUser {
              id = "43"
            },
            buildUser {
              id = "44"
            },
            buildUser {
              id = "45"
            },
        )
      }
    }
    assertEquals(expectedData, dataFromStore)

    // Page after
    val query3 = UsersQuery(first = Optional.Present(2), after = Optional.Present("xx45"))
    val data3 = UsersQuery.Data {
      users = buildUserConnection {
        pageInfo = buildPageInfo {
          startCursor = "xx46"
          endCursor = "xx47"
        }
        nodes = listOf(
            buildUser {
              id = "46"
            },
            buildUser {
              id = "47"
            },
        )
      }
    }
    apolloStore.writeOperation(query3, data3)
    dataFromStore = apolloStore.readOperation(query1).data
    expectedData = UsersQuery.Data {
      users = buildUserConnection {
        pageInfo = buildPageInfo {
          startCursor = "xx42"
          endCursor = "xx47"
        }
        nodes = listOf(
            buildUser {
              id = "42"
            },
            buildUser {
              id = "43"
            },
            buildUser {
              id = "44"
            },
            buildUser {
              id = "45"
            },
            buildUser {
              id = "46"
            },
            buildUser {
              id = "47"
            },
        )
      }
    }
    assertEquals(expectedData, dataFromStore)

    // Page before
    val query4 = UsersQuery(last = Optional.Present(2), before = Optional.Present("xx42"))
    val data4 = UsersQuery.Data {
      users = buildUserConnection {
        pageInfo = buildPageInfo {
          startCursor = "xx40"
          endCursor = "xx41"
        }
        nodes = listOf(
            buildUser {
              id = "40"
            },
            buildUser {
              id = "41"
            },
        )
      }
    }
    apolloStore.writeOperation(query4, data4)
    dataFromStore = apolloStore.readOperation(query1).data
    expectedData = UsersQuery.Data {
      users = buildUserConnection {
        pageInfo = buildPageInfo {
          startCursor = "xx40"
          endCursor = "xx47"
        }
        nodes = listOf(
            buildUser {
              id = "40"
            },
            buildUser {
              id = "41"
            },
            buildUser {
              id = "42"
            },
            buildUser {
              id = "43"
            },
            buildUser {
              id = "44"
            },
            buildUser {
              id = "45"
            },
            buildUser {
              id = "46"
            },
            buildUser {
              id = "47"
            },
        )
      }
    }
    assertEquals(expectedData, dataFromStore)

    // Non-contiguous page (should reset)
    val query5 = UsersQuery(first = Optional.Present(2), after = Optional.Present("xx50"))
    val data5 = UsersQuery.Data {
      users = buildUserConnection {
        pageInfo = buildPageInfo {
          startCursor = "xx50"
          endCursor = "xx51"
        }
        nodes = listOf(
            buildUser {
              id = "50"
            },
            buildUser {
              id = "51"
            },
        )
      }
    }
    apolloStore.writeOperation(query5, data5)
    dataFromStore = apolloStore.readOperation(query1).data
    assertEquals(data5, dataFromStore)

    // Empty page (should keep previous result)
    val query6 = UsersQuery(first = Optional.Present(2), after = Optional.Present("xx51"))
    val data6 = UsersQuery.Data {
      users = buildUserConnection {
        pageInfo = buildPageInfo {
          startCursor = null
          endCursor = null
        }
        nodes = emptyList()

      }
    }
    apolloStore.writeOperation(query6, data6)
    dataFromStore = apolloStore.readOperation(query1).data
    assertEquals(data5, dataFromStore)
  }
}

