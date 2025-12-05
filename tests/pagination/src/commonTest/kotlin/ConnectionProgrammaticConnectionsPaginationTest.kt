package pagination

import com.apollographql.apollo.api.Optional
import com.apollographql.cache.normalized.CacheManager
import com.apollographql.cache.normalized.api.ConnectionEmbeddedFieldsProvider
import com.apollographql.cache.normalized.api.ConnectionFieldKeyGenerator
import com.apollographql.cache.normalized.api.ConnectionMetadataGenerator
import com.apollographql.cache.normalized.api.ConnectionRecordMerger
import com.apollographql.cache.normalized.api.FieldPolicyCacheResolver
import com.apollographql.cache.normalized.api.NormalizedCacheFactory
import com.apollographql.cache.normalized.api.TypePolicyCacheKeyGenerator
import com.apollographql.cache.normalized.memory.MemoryCacheFactory
import com.apollographql.cache.normalized.testing.SqlNormalizedCacheFactory
import com.apollographql.cache.normalized.testing.runTest
import pagination.connectionProgrammaticConnections.UsersQuery
import pagination.connectionProgrammaticConnections.cache.Cache
import pagination.connectionProgrammaticConnections.type.UserConnection
import pagination.connectionProgrammaticConnections.type.buildPageInfo
import pagination.connectionProgrammaticConnections.type.buildUser
import pagination.connectionProgrammaticConnections.type.buildUserConnection
import pagination.connectionProgrammaticConnections.type.buildUserEdge
import kotlin.test.Test
import kotlin.test.assertEquals

class ConnectionProgrammaticConnectionsPaginationTest {
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
    val connectionTypes = setOf(UserConnection.type.name)
    val cacheManager = CacheManager(
        normalizedCacheFactory = cacheFactory,
        cacheKeyGenerator = TypePolicyCacheKeyGenerator(Cache.typePolicies),
        cacheResolver = FieldPolicyCacheResolver(Cache.fieldPolicies),
        metadataGenerator = ConnectionMetadataGenerator(connectionTypes),
        recordMerger = ConnectionRecordMerger,
        fieldKeyGenerator = ConnectionFieldKeyGenerator(connectionTypes),
        embeddedFieldsProvider = ConnectionEmbeddedFieldsProvider(connectionTypes),
    )
    cacheManager.clearAll()

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
    cacheManager.writeOperation(query1, data1)
    var dataFromStore = cacheManager.readOperation(query1).data
    assertEquals(data1, dataFromStore)
    assertChainedCachesAreEqual(cacheManager)

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
    cacheManager.writeOperation(query2, data2)
    dataFromStore = cacheManager.readOperation(query1).data
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
    assertChainedCachesAreEqual(cacheManager)

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
    cacheManager.writeOperation(query3, data3)
    dataFromStore = cacheManager.readOperation(query1).data
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
    assertChainedCachesAreEqual(cacheManager)

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
    cacheManager.writeOperation(query4, data4)
    dataFromStore = cacheManager.readOperation(query1).data
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
    assertChainedCachesAreEqual(cacheManager)

    // Non-contiguous page (should be ignored)
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
    cacheManager.writeOperation(query5, data5)
    dataFromStore = cacheManager.readOperation(query1).data
    assertEquals(expectedData, dataFromStore)
    assertChainedCachesAreEqual(cacheManager)

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
    cacheManager.writeOperation(query6, data6)
    dataFromStore = cacheManager.readOperation(query1).data
    assertEquals(expectedData, dataFromStore)
    assertChainedCachesAreEqual(cacheManager)

    // First page again (should reset)
    val query7 = UsersQuery(first = Optional.Present(2))
    val data7 = UsersQuery.Data {
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
    cacheManager.writeOperation(query7, data7)
    dataFromStore = cacheManager.readOperation(query7).data
    assertEquals(data7, dataFromStore)
    assertChainedCachesAreEqual(cacheManager)
  }
}

