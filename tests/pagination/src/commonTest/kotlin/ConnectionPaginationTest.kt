package pagination

import com.apollographql.apollo.api.Error
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
import pagination.connection.UsersQuery
import pagination.connection.pagination.Pagination
import pagination.connection.type.buildPageInfo
import pagination.connection.type.buildUser
import pagination.connection.type.buildUserConnection
import pagination.connection.type.buildUserEdge
import kotlin.test.Test
import kotlin.test.assertEquals

class ConnectionPaginationTest {
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
        edges = listOf(
            buildUserEdge {
              cursor = "xx42"
              node = buildUser {
                id = "42"
              }
            },
            buildUserEdge {
              cursor = "xx43"
              node = buildUser {
                id = "43"
              }
            },
        )
      }
    }
    apolloStore.writeOperation(query1, data1)
    var dataFromStore = apolloStore.readOperation(query1).data
    assertEquals(data1, dataFromStore)
    assertChainedCachesAreEqual(apolloStore)

    // Page after
    val query2 = UsersQuery(first = Optional.Present(2), after = Optional.Present("xx43"))
    val data2 = UsersQuery.Data {
      users = buildUserConnection {
        pageInfo = buildPageInfo {
          startCursor = "xx44"
          endCursor = "xx45"
        }
        edges = listOf(
            buildUserEdge {
              cursor = "xx44"
              node = buildUser {
                id = "44"
              }
            },
            buildUserEdge {
              cursor = "xx45"
              node = buildUser {
                id = "45"
              }
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
        edges = listOf(
            buildUserEdge {
              cursor = "xx42"
              node = buildUser {
                id = "42"
              }
            },
            buildUserEdge {
              cursor = "xx43"
              node = buildUser {
                id = "43"
              }
            },
            buildUserEdge {
              cursor = "xx44"
              node = buildUser {
                id = "44"
              }
            },
            buildUserEdge {
              cursor = "xx45"
              node = buildUser {
                id = "45"
              }
            },
        )
      }
    }
    assertEquals(expectedData, dataFromStore)
    assertChainedCachesAreEqual(apolloStore)

    // Page after
    val query3 = UsersQuery(first = Optional.Present(2), after = Optional.Present("xx45"))
    val data3 = UsersQuery.Data {
      users = buildUserConnection {
        pageInfo = buildPageInfo {
          startCursor = "xx46"
          endCursor = "xx47"
        }
        edges = listOf(
            buildUserEdge {
              cursor = "xx46"
              node = buildUser {
                id = "46"
              }
            },
            buildUserEdge {
              cursor = "xx47"
              node = buildUser {
                id = "47"
              }
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
        edges = listOf(
            buildUserEdge {
              cursor = "xx42"
              node = buildUser {
                id = "42"
              }
            },
            buildUserEdge {
              cursor = "xx43"
              node = buildUser {
                id = "43"
              }
            },
            buildUserEdge {
              cursor = "xx44"
              node = buildUser {
                id = "44"
              }
            },
            buildUserEdge {
              cursor = "xx45"
              node = buildUser {
                id = "45"
              }
            },
            buildUserEdge {
              cursor = "xx46"
              node = buildUser {
                id = "46"
              }
            },
            buildUserEdge {
              cursor = "xx47"
              node = buildUser {
                id = "47"
              }
            },
        )
      }
    }
    assertEquals(expectedData, dataFromStore)
    assertChainedCachesAreEqual(apolloStore)

    // Page before
    val query4 = UsersQuery(last = Optional.Present(2), before = Optional.Present("xx42"))
    val data4 = UsersQuery.Data {
      users = buildUserConnection {
        pageInfo = buildPageInfo {
          startCursor = "xx40"
          endCursor = "xx41"
        }
        edges = listOf(
            buildUserEdge {
              cursor = "xx40"
              node = buildUser {
                id = "40"
              }
            },
            buildUserEdge {
              cursor = "xx41"
              node = buildUser {
                id = "41"
              }
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
        edges = listOf(
            buildUserEdge {
              cursor = "xx40"
              node = buildUser {
                id = "40"
              }
            },
            buildUserEdge {
              cursor = "xx41"
              node = buildUser {
                id = "41"
              }
            },
            buildUserEdge {
              cursor = "xx42"
              node = buildUser {
                id = "42"
              }
            },
            buildUserEdge {
              cursor = "xx43"
              node = buildUser {
                id = "43"
              }
            },
            buildUserEdge {
              cursor = "xx44"
              node = buildUser {
                id = "44"
              }
            },
            buildUserEdge {
              cursor = "xx45"
              node = buildUser {
                id = "45"
              }
            },
            buildUserEdge {
              cursor = "xx46"
              node = buildUser {
                id = "46"
              }
            },
            buildUserEdge {
              cursor = "xx47"
              node = buildUser {
                id = "47"
              }
            },
        )
      }
    }
    assertEquals(expectedData, dataFromStore)
    assertChainedCachesAreEqual(apolloStore)

    // Non-contiguous page (should reset)
    val query5 = UsersQuery(first = Optional.Present(2), after = Optional.Present("xx50"))
    val data5 = UsersQuery.Data {
      users = buildUserConnection {
        pageInfo = buildPageInfo {
          startCursor = "xx50"
          endCursor = "xx51"
        }
        edges = listOf(
            buildUserEdge {
              cursor = "xx50"
              node = buildUser {
                id = "50"
              }
            },
            buildUserEdge {
              cursor = "xx51"
              node = buildUser {
                id = "51"
              }
            },
        )
      }
    }
    apolloStore.writeOperation(query5, data5)
    dataFromStore = apolloStore.readOperation(query1).data
    assertEquals(data5, dataFromStore)
    assertChainedCachesAreEqual(apolloStore)

    // Empty page (should keep previous result)
    val query6 = UsersQuery(first = Optional.Present(2), after = Optional.Present("xx51"))
    val data6 = UsersQuery.Data {
      users = buildUserConnection {
        pageInfo = buildPageInfo {
          startCursor = null
          endCursor = null
        }
        edges = emptyList()
      }
    }
    apolloStore.writeOperation(query6, data6)
    dataFromStore = apolloStore.readOperation(query1).data
    assertEquals(data5, dataFromStore)
    assertChainedCachesAreEqual(apolloStore)
  }

  @Test
  fun errorMemoryCache() {
    errorTest(MemoryCacheFactory())
  }

  @Test
  fun errorSqlCache() {
    errorTest(SqlNormalizedCacheFactory())
  }

  @Test
  fun errorChainedCache() {
    errorTest(MemoryCacheFactory().chain(SqlNormalizedCacheFactory()))
  }

  private fun errorTest(cacheFactory: NormalizedCacheFactory) = runTest {
    val apolloStore = ApolloStore(
        normalizedCacheFactory = cacheFactory,
        cacheKeyGenerator = TypePolicyCacheKeyGenerator,
        metadataGenerator = ConnectionMetadataGenerator(Pagination.connectionTypes),
        cacheResolver = FieldPolicyCacheResolver,
        recordMerger = ConnectionRecordMerger
    )
    apolloStore.clearAll()
    val query = UsersQuery(first = Optional.Present(2))
    apolloStore.writeOperation(
        operation = query,
        data = UsersQuery.Data { users = null },
        errors = listOf(Error.Builder("An error occurred.").path(listOf("users")).build())
    )
    val responseFromStore = apolloStore.readOperation(query)
    assertEquals(UsersQuery.Data { users = null }, responseFromStore.data)
    assertEquals(1, responseFromStore.errors?.size)
    assertEquals("An error occurred.", responseFromStore.errors?.firstOrNull()?.message)
    assertEquals(listOf("users"), responseFromStore.errors?.firstOrNull()?.path)
  }
}
