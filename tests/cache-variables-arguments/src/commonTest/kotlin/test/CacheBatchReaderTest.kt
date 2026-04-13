@file:Suppress("DEPRECATION")

package test

import cache.include.TwoFieldsQuery
import cache.include.fragment.UserId
import cache.include.fragment.UserName
import com.apollographql.cache.normalized.CacheManager
import com.apollographql.cache.normalized.api.DefaultCacheKeyGenerator
import com.apollographql.cache.normalized.api.DefaultCacheResolver
import com.apollographql.cache.normalized.memory.MemoryCacheFactory
import com.apollographql.cache.normalized.testing.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * See https://github.com/apollographql/apollo-kotlin/issues/6901
 */
class CacheBatchReaderTest {
  @Test
  fun fieldsWithIncludeDirectiveDoNotOverwriteEachOther() = runTest {
    val operation = TwoFieldsQuery(true)
    val store = CacheManager(MemoryCacheFactory(), DefaultCacheKeyGenerator, DefaultCacheResolver)
    store.writeOperation(operation, TwoFieldsQuery.Data(__typename = "Query", userId = UserId(UserId.User("42")), userName = UserName(UserName.User("foo"))))
    val data = store.readOperation(operation).data!!
    assertEquals("foo", data.userName.user!!.name)
  }
}
