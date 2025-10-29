package test.fragmentnormalizer

import com.apollographql.apollo.ApolloClient
import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.api.DefaultCacheKeyGenerator
import com.apollographql.cache.normalized.api.DefaultCacheResolver
import com.apollographql.cache.normalized.api.IdCacheKeyGenerator
import com.apollographql.cache.normalized.apolloStore
import com.apollographql.cache.normalized.internal.normalized
import com.apollographql.cache.normalized.memory.MemoryCacheFactory
import com.apollographql.cache.normalized.normalizedCache
import com.apollographql.cache.normalized.testing.append
import com.apollographql.cache.normalized.testing.runTest
import fragmentnormalizer.fragment.ConversationFragment
import fragmentnormalizer.fragment.ConversationFragmentImpl
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class FragmentNormalizerTest {
  @Test
  fun test() = runTest {
    val cacheFactory = MemoryCacheFactory()

    val apolloClient = ApolloClient.Builder()
        .serverUrl("https:/example.com")
        .normalizedCache(cacheFactory, cacheKeyGenerator = DefaultCacheKeyGenerator, cacheResolver = DefaultCacheResolver)
        .build()

    var fragment1 = ConversationFragment(
        "1",
        ConversationFragment.Author(
            "John Doe",
        ),
        false
    )

    /**
     * This is not using .copy() because this test also runs in Java and Java doesn't have copy()
     */
    val fragment1Read = ConversationFragment(
        "1",
        ConversationFragment.Author(
            "John Doe",
        ),
        true
    )
    val fragment2 = ConversationFragment(
        "2",
        ConversationFragment.Author(
            "Yayyy Pancakes!",
        ),
        false
    )

    /**
     * This is not using .copy() because this test also runs in Java and Java doesn't have copy()
     */
    val fragment2Read = ConversationFragment(
        "2",
        ConversationFragment.Author(
            "Yayyy Pancakes!",
        ),
        true
    )
    apolloClient.apolloStore.writeFragment(
        ConversationFragmentImpl(),
        CacheKey(fragment1.id),
        fragment1Read,
    )

    apolloClient.apolloStore.writeFragment(
        ConversationFragmentImpl(),
        CacheKey(fragment2.id),
        fragment2Read,
    )

    fragment1 = apolloClient.apolloStore.readFragment(
        ConversationFragmentImpl(),
        CacheKey(fragment1.id),
    ).data

    assertEquals("John Doe", fragment1.author.fullName)
  }

  @Test
  fun rootKeyIsNotSkipped() = runTest {
    val fragment = ConversationFragment(
        "1",
        ConversationFragment.Author(
            "John Doe",
        ),
        false
    )

    val records = fragment.normalized(
        ConversationFragmentImpl(),
        rootKey = CacheKey("1"),
        cacheKeyGenerator = IdCacheKeyGenerator(),
    )

    assertContains(records.keys, CacheKey("1").append("author"))
  }
}
