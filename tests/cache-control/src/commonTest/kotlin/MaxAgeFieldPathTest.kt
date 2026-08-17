package test

import com.apollographql.apollo.exception.CacheMissException
import com.apollographql.cache.normalized.CacheManager
import com.apollographql.cache.normalized.api.CacheControlCacheResolver
import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.api.DefaultCacheKeyGenerator
import com.apollographql.cache.normalized.api.DefaultCacheResolver
import com.apollographql.cache.normalized.api.DefaultMaxAgeProvider
import com.apollographql.cache.normalized.api.GlobalMaxAgeProvider
import com.apollographql.cache.normalized.api.MaxAgeContext
import com.apollographql.cache.normalized.api.MaxAgeProvider
import com.apollographql.cache.normalized.internal.normalized
import com.apollographql.cache.normalized.memory.MemoryCacheFactory
import com.apollographql.cache.normalized.testing.runTest
import programmatic.GetReaderBookTitleQuery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A [GlobalMaxAgeProvider] gives every field the same max age and never reads the field path, so both
 * callers resolve it once and skip building the path. Any other provider has to be handed the whole
 * path, root first, exactly as before.
 */
class MaxAgeFieldPathTest {
  /**
   * Records the path it is asked about, and answers a fixed max age without delegating - a delegating
   * recorder would also record the sub-paths its delegate walks, and what is under test here is the
   * path the caller builds.
   */
  private class RecordingMaxAgeProvider(private val maxAge: Duration) : MaxAgeProvider {
    val paths = mutableListOf<List<String>>()

    override fun getMaxAge(maxAgeContext: MaxAgeContext): Duration {
      paths.add(maxAgeContext.fieldPath.map { "${it.type.name}.${it.name}" })
      return maxAge
    }
  }

  @Test
  fun normalizingHandsTheWholeFieldPathToANonGlobalProvider() = runTest {
    val provider = RecordingMaxAgeProvider(60.seconds)

    readerBookTitleData().normalized(
        GetReaderBookTitleQuery(),
        DefaultCacheKeyGenerator,
        maxAgeProvider = provider,
    )

    // Deepest first: the normalizer replaces a field's value, which normalizes everything under it,
    // before asking for that field's max age.
    assertEquals(
        listOf(
            listOf("Query.data", "Reader.reader", "Book.book", "String.title"),
            listOf("Query.data", "Reader.reader", "Book.book"),
            listOf("Query.data", "Reader.reader"),
        ),
        provider.paths,
    )
  }

  @Test
  fun readingHandsTheWholeFieldPathToANonGlobalProvider() = runTest {
    val provider = RecordingMaxAgeProvider(60.seconds)
    val cacheManager = CacheManager(
        normalizedCacheFactory = MemoryCacheFactory(),
        cacheKeyGenerator = DefaultCacheKeyGenerator,
        cacheResolver = CacheControlCacheResolver(provider, DefaultCacheResolver),
    )
    val query = GetReaderBookTitleQuery()

    // The resolver only looks at the max age of a field it knows the received date of.
    cacheManager.writeOperation(query, readerBookTitleData(), cacheHeaders = receivedDate(currentTimeSeconds()))
    provider.paths.clear()

    assertEquals("Le Petit Prince", cacheManager.readOperation(query).data?.reader?.book?.title)

    assertEquals(
        listOf(
            listOf("Query.data", "Reader.reader"),
            listOf("Query.data", "Reader.reader", "Book.book"),
            listOf("Query.data", "Reader.reader", "Book.book", "String.title"),
        ),
        provider.paths,
    )
  }

  /**
   * The short-circuit has to answer what the provider would have answered: a max age of zero means the
   * field is not stored at all.
   */
  @Test
  fun aGlobalMaxAgeOfZeroStoresNothing() = runTest {
    val records = readerBookTitleData().normalized(
        GetReaderBookTitleQuery(),
        DefaultCacheKeyGenerator,
        maxAgeProvider = GlobalMaxAgeProvider(Duration.ZERO),
    )

    assertEquals(emptyMap(), records[CacheKey.QUERY_ROOT]?.fields ?: emptyMap())
  }

  @Test
  fun anInfiniteGlobalMaxAgeStoresEverything() = runTest {
    val records = readerBookTitleData().normalized(
        GetReaderBookTitleQuery(),
        DefaultCacheKeyGenerator,
        maxAgeProvider = DefaultMaxAgeProvider,
    )

    assertTrue(records.getValue(CacheKey.QUERY_ROOT).fields.containsKey("reader"))
  }

  @Test
  fun aGlobalMaxAgeOfZeroMakesEveryReadFieldStale() = runTest {
    val cacheManager = CacheManager(
        normalizedCacheFactory = MemoryCacheFactory(),
        cacheKeyGenerator = DefaultCacheKeyGenerator,
        cacheResolver = CacheControlCacheResolver(GlobalMaxAgeProvider(Duration.ZERO), DefaultCacheResolver),
    )
    val query = GetReaderBookTitleQuery()

    // Written with the default provider, so the records exist and carry a received date.
    cacheManager.writeOperation(query, readerBookTitleData(), cacheHeaders = receivedDate(currentTimeSeconds()))

    val exception = cacheManager.readOperation(query).exception
    assertIs<CacheMissException>(exception)
    assertTrue(exception.stale)
  }

  @Test
  fun anInfiniteGlobalMaxAgeMakesNoReadFieldStale() = runTest {
    val cacheManager = CacheManager(
        normalizedCacheFactory = MemoryCacheFactory(),
        cacheKeyGenerator = DefaultCacheKeyGenerator,
        cacheResolver = CacheControlCacheResolver(DefaultMaxAgeProvider, DefaultCacheResolver),
    )
    val query = GetReaderBookTitleQuery()

    cacheManager.writeOperation(query, readerBookTitleData(), cacheHeaders = receivedDate(currentTimeSeconds()))

    assertEquals("Le Petit Prince", cacheManager.readOperation(query).data?.reader?.book?.title)
  }

  private fun readerBookTitleData() = GetReaderBookTitleQuery.Data(
      reader = GetReaderBookTitleQuery.Reader(
          book = GetReaderBookTitleQuery.Book(title = "Le Petit Prince"),
      ),
  )
}
