package test.circular

import circular.GetUserQuery
import com.apollographql.cache.normalized.CacheManager
import com.apollographql.cache.normalized.api.DefaultCacheKeyGenerator
import com.apollographql.cache.normalized.api.DefaultCacheResolver
import com.apollographql.cache.normalized.memory.MemoryCacheFactory
import com.apollographql.cache.normalized.testing.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CircularCacheReadTest {
  @Test
  fun circularReferenceDoesNotStackOverflow() = runTest {
    val cacheManager =
      CacheManager(MemoryCacheFactory(), cacheKeyGenerator = DefaultCacheKeyGenerator, cacheResolver = DefaultCacheResolver)

    val operation = GetUserQuery()

    /**
     * Create a record that references itself. It should not create a stack overflow
     */
    val data = GetUserQuery.Data(
        GetUserQuery.User(
            "42",
            GetUserQuery.Friend(
                "42",
                "User"
            ),
            "User",
        )
    )

    cacheManager.writeOperation(operation, data)
    val result = cacheManager.readOperation(operation).data!!
    assertEquals("42", result.user.friend.id)
  }
}
