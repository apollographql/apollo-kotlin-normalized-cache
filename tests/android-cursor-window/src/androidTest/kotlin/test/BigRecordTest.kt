@file:OptIn(ApolloExperimental::class)

package test

import android.os.Environment
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.apollographql.apollo.annotations.ApolloExperimental
import com.apollographql.apollo.exception.apolloExceptionHandler
import com.apollographql.cache.normalized.CacheManager
import com.apollographql.cache.normalized.api.FieldPolicyCacheResolver
import com.apollographql.cache.normalized.api.TypePolicyCacheKeyGenerator
import com.apollographql.cache.normalized.sql.bundled.SqlNormalizedCacheFactory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import test.cache.Cache
import java.io.File
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class BigRecordTest {
  @Test
  fun test() = runBlocking {
    // Force crash is reading fails
    apolloExceptionHandler = { throw it }

    val cacheManager = CacheManager(
        normalizedCacheFactory = SqlNormalizedCacheFactory(),
        cacheKeyGenerator = TypePolicyCacheKeyGenerator(Cache.typePolicies),
        cacheResolver = FieldPolicyCacheResolver(Cache.fieldPolicies),
    ).also { it.clearAll() }

    repeat(100) { iteration ->
      Log.i("BigRecordTest", "iteration=$iteration")

      val query = UsersQuery(
          List(2000) { index ->
            val id = "$iteration-$index"
            id
          },
      )
      val data = UsersQuery.Data(
          users = List(2000) { index ->
            val id = "$iteration-$index"
            UsersQuery.User(
                __typename = "User",
                id = id,
                firstName = "FirstName $id",
                lastName = "LastName $id",
            )
          },
      )
      cacheManager.writeOperation(query, data)

      val cachedData = cacheManager.readOperation(query).data
      assertEquals(data, cachedData)
    }
  }

  @After
  fun dumpDb() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val db = context.cacheDir.resolve("apollo.db")
    val out =
      File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "apollo-${System.currentTimeMillis()}.db")
    db.copyTo(out, overwrite = true)
    // Should be /storage/emulated/0/Download/apollo.db
    Log.i("BigRecordTest", "DB copied to ${out.absolutePath}")
  }
}
