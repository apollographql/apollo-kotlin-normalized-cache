package test

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.exception.CacheMissException
import com.apollographql.apollo.mpp.currentTimeMillis
import com.apollographql.cache.normalized.FetchPolicy
import com.apollographql.cache.normalized.api.ApolloCacheHeaders
import com.apollographql.cache.normalized.api.CacheControlCacheResolver
import com.apollographql.cache.normalized.api.CacheHeaders
import com.apollographql.cache.normalized.api.DefaultRecordMerger
import com.apollographql.cache.normalized.api.GlobalMaxAgeProvider
import com.apollographql.cache.normalized.api.MaxAge
import com.apollographql.cache.normalized.api.NormalizedCacheFactory
import com.apollographql.cache.normalized.api.SchemaCoordinatesMaxAgeProvider
import com.apollographql.cache.normalized.apolloStore
import com.apollographql.cache.normalized.clock
import com.apollographql.cache.normalized.fetchPolicy
import com.apollographql.cache.normalized.internal.normalized
import com.apollographql.cache.normalized.maxStale
import com.apollographql.cache.normalized.memory.MemoryCacheFactory
import com.apollographql.cache.normalized.normalizedCache
import com.apollographql.cache.normalized.sql.SqlNormalizedCacheFactory
import com.apollographql.cache.normalized.testing.runTest
import declarative.cache.Cache.cache
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import declarative.GetCompanyQuery as DeclarativeGetCompanyQuery
import declarative.GetProjectQuery as DeclarativeGetProjectQuery
import declarative.GetUserAdminQuery as DeclarativeGetUserAdminQuery
import declarative.GetUserEmailQuery as DeclarativeGetUserEmailQuery
import declarative.GetUserNameQuery as DeclarativeGetUserNameQuery
import declarative.GetUserQuery as DeclarativeGetUserQuery
import programmatic.GetCompanyQuery as ProgrammaticGetCompanyQuery
import programmatic.GetUserAdminQuery as ProgrammaticGetUserAdminQuery
import programmatic.GetUserEmailQuery as ProgrammaticGetUserEmailQuery
import programmatic.GetUserNameQuery as ProgrammaticGetUserNameQuery
import programmatic.GetUserQuery as ProgrammaticGetUserQuery

class ClientSideCacheControlTest {
  @Test
  fun globalMaxAgeMemoryCache() {
    globalMaxAge(MemoryCacheFactory())
  }

  @Test
  fun globalMaxAgeSqlCache() {
    globalMaxAge(SqlNormalizedCacheFactory())
  }

  @Test
  fun globalMaxAgeChainedCache() {
    globalMaxAge(MemoryCacheFactory().chain(SqlNormalizedCacheFactory()))
  }

  @Test
  fun programmaticMaxAgeMemoryCache() {
    programmaticMaxAge(MemoryCacheFactory())
  }

  @Test
  fun programmaticMaxAgeSqlCache() {
    programmaticMaxAge(SqlNormalizedCacheFactory())
  }

  @Test
  fun programmaticMaxAgeChainedCache() {
    programmaticMaxAge(MemoryCacheFactory().chain(SqlNormalizedCacheFactory()))
  }

  @Test
  fun declarativeMaxAgeMemoryCache() {
    declarativeMaxAge(MemoryCacheFactory())
  }

  @Test
  fun declarativeMaxAgeSqlCache() {
    declarativeMaxAge(SqlNormalizedCacheFactory())
  }

  @Test
  fun declarativeMaxAgeChainedCache() {
    declarativeMaxAge(MemoryCacheFactory().chain(SqlNormalizedCacheFactory()))
  }

  @Test
  fun configureClockMemoryCache() {
    configureClock(MemoryCacheFactory())
  }

  @Test
  fun configureClockSqlCache() {
    configureClock(SqlNormalizedCacheFactory())
  }

  @Test
  fun configureClockChainedCache() {
    configureClock(MemoryCacheFactory().chain(SqlNormalizedCacheFactory()))
  }

  private fun globalMaxAge(normalizedCacheFactory: NormalizedCacheFactory) = runTest {
    val maxAge = 10
    val client = ApolloClient.Builder()
        .normalizedCache(
            normalizedCacheFactory = normalizedCacheFactory,
            cacheResolver = CacheControlCacheResolver(GlobalMaxAgeProvider(maxAge.seconds)),
        )
        .serverUrl("unused")
        .build()
    client.apolloStore.clearAll()

    val query = ProgrammaticGetUserQuery()
    val data = ProgrammaticGetUserQuery.Data(ProgrammaticGetUserQuery.User("John", "john@doe.com", true))

    val records = data.normalized(query).values

    client.apolloStore.accessCache {
      // store records in the past
      it.merge(records, receivedDate(currentTimeSeconds() - 15), DefaultRecordMerger)
    }

    val e = client.query(ProgrammaticGetUserQuery()).fetchPolicy(FetchPolicy.CacheOnly).execute().exception as CacheMissException
    assertTrue(e.stale)

    // with max stale, should succeed
    val response1 = client.query(ProgrammaticGetUserQuery()).fetchPolicy(FetchPolicy.CacheOnly)
        .maxStale(10.seconds)
        .execute()
    assertTrue(response1.data?.user?.name == "John")

    client.apolloStore.accessCache {
      // update records to be in the present
      it.merge(records, receivedDate(currentTimeSeconds()), DefaultRecordMerger)
    }

    val response2 = client.query(ProgrammaticGetUserQuery()).fetchPolicy(FetchPolicy.CacheOnly).execute()
    assertTrue(response2.data?.user?.name == "John")
  }

  private fun programmaticMaxAge(normalizedCacheFactory: NormalizedCacheFactory) = runTest {
    val maxAgeProvider = SchemaCoordinatesMaxAgeProvider(
        mapOf(
            "User" to MaxAge.Duration(10.seconds),
            "User.name" to MaxAge.Duration(5.seconds),
            "User.email" to MaxAge.Duration(2.seconds),
        ),
        defaultMaxAge = 20.seconds,
    )

    val client = ApolloClient.Builder()
        .normalizedCache(
            normalizedCacheFactory = normalizedCacheFactory,
            cacheResolver = CacheControlCacheResolver(maxAgeProvider),
        )
        .serverUrl("unused")
        .build()
    client.apolloStore.clearAll()

    // Store records 25 seconds ago, more than default max age: should cache miss
    mergeProgrammaticCompanyQueryResults(client, 25)
    var e = client.query(ProgrammaticGetCompanyQuery()).fetchPolicy(FetchPolicy.CacheOnly).execute().exception as CacheMissException
    assertTrue(e.stale)

    // Store records 15 seconds ago, less than default max age: should not cache miss
    mergeProgrammaticCompanyQueryResults(client, 15)
    // Company fields are not configured so the default max age should be used
    val companyResponse = client.query(ProgrammaticGetCompanyQuery()).fetchPolicy(FetchPolicy.CacheOnly).execute()
    assertTrue(companyResponse.data?.company?.id == "42")


    // Store records 15 seconds ago, more than max age for User: should cache miss
    mergeUserQueryResults(client, 15)
    e = client.query(ProgrammaticGetUserAdminQuery()).fetchPolicy(FetchPolicy.CacheOnly).execute().exception as CacheMissException
    assertTrue(e.stale)

    // Store records 5 seconds ago, less than max age for User: should not cache miss
    mergeUserQueryResults(client, 5)
    val userAdminResponse = client.query(ProgrammaticGetUserAdminQuery()).fetchPolicy(FetchPolicy.CacheOnly).execute()
    assertTrue(userAdminResponse.data?.user?.admin == true)


    // Store records 10 seconds ago, more than max age for User.name: should cache miss
    mergeUserQueryResults(client, 10)
    e = client.query(ProgrammaticGetUserNameQuery()).fetchPolicy(FetchPolicy.CacheOnly).execute().exception as CacheMissException
    assertTrue(e.stale)

    // Store records 2 seconds ago, less than max age for User.name: should not cache miss
    mergeUserQueryResults(client, 2)
    val userNameResponse = client.query(ProgrammaticGetUserNameQuery()).fetchPolicy(FetchPolicy.CacheOnly).execute()
    assertTrue(userNameResponse.data?.user?.name == "John")


    // Store records 5 seconds ago, more than max age for User.email: should cache miss
    mergeUserQueryResults(client, 5)
    e = client.query(ProgrammaticGetUserEmailQuery()).fetchPolicy(FetchPolicy.CacheOnly).execute().exception as CacheMissException
    assertTrue(e.stale)

    // Store records 1 second ago, less than max age for User.email: should not cache miss
    mergeUserQueryResults(client, 1)
    val userEmailResponse = client.query(ProgrammaticGetUserEmailQuery()).fetchPolicy(FetchPolicy.CacheOnly).execute()
    assertTrue(userEmailResponse.data?.user?.email == "john@doe.com")
  }

  private suspend fun mergeProgrammaticCompanyQueryResults(client: ApolloClient, secondsAgo: Int) {
    val data = ProgrammaticGetCompanyQuery.Data(ProgrammaticGetCompanyQuery.Company(id = "42"))
    val records = data.normalized(ProgrammaticGetCompanyQuery()).values
    client.apolloStore.accessCache {
      it.merge(records, receivedDate(currentTimeSeconds() - secondsAgo), DefaultRecordMerger)
    }
  }

  private fun declarativeMaxAge(normalizedCacheFactory: NormalizedCacheFactory) = runTest {
    val client = ApolloClient.Builder()
        .cache(
            normalizedCacheFactory = normalizedCacheFactory,
            defaultMaxAge = 20.seconds,
        )
        .serverUrl("unused")
        .build()
    client.apolloStore.clearAll()

    // Store records 25 seconds ago, more than default max age: should cache miss
    mergeDeclarativeCompanyQueryResults(client, 25)
    var e = client.query(DeclarativeGetCompanyQuery()).fetchPolicy(FetchPolicy.CacheOnly).execute().exception as CacheMissException
    assertTrue(e.stale)

    // Store records 15 seconds ago, less than default max age: should not cache miss
    mergeDeclarativeCompanyQueryResults(client, 15)
    // Company fields are not configured so the default max age should be used
    val companyResponse = client.query(DeclarativeGetCompanyQuery()).fetchPolicy(FetchPolicy.CacheOnly).execute()
    assertTrue(companyResponse.data?.company?.id == "42")


    // Store records 15 seconds ago, more than max age for User: should cache miss
    mergeUserQueryResults(client, 15)
    e = client.query(DeclarativeGetUserAdminQuery()).fetchPolicy(FetchPolicy.CacheOnly).execute().exception as CacheMissException
    assertTrue(e.stale)

    // Store records 5 seconds ago, less than max age for User: should not cache miss
    mergeUserQueryResults(client, 5)
    val userAdminResponse = client.query(DeclarativeGetUserAdminQuery()).fetchPolicy(FetchPolicy.CacheOnly).execute()
    assertTrue(userAdminResponse.data?.user?.admin == true)


    // Store records 10 seconds ago, more than max age for User.name: should cache miss
    mergeUserQueryResults(client, 10)
    e = client.query(DeclarativeGetUserNameQuery()).fetchPolicy(FetchPolicy.CacheOnly).execute().exception as CacheMissException
    assertTrue(e.stale)

    // Store records 2 seconds ago, less than max age for User.name: should not cache miss
    mergeUserQueryResults(client, 2)
    val userNameResponse = client.query(DeclarativeGetUserNameQuery()).fetchPolicy(FetchPolicy.CacheOnly).execute()
    assertTrue(userNameResponse.data?.user?.name == "John")


    // Store records 5 seconds ago, more than max age for User.email: should cache miss
    mergeUserQueryResults(client, 5)
    e = client.query(DeclarativeGetUserEmailQuery()).fetchPolicy(FetchPolicy.CacheOnly).execute().exception as CacheMissException
    assertTrue(e.stale)

    // Store records 1 second ago, less than max age for User.email: should not cache miss
    mergeUserQueryResults(client, 1)
    val userEmailResponse = client.query(DeclarativeGetUserEmailQuery()).fetchPolicy(FetchPolicy.CacheOnly).execute()
    assertTrue(userEmailResponse.data?.user?.email == "john@doe.com")

    // Store records 10 second ago, less that max age for Node: should not cache miss
    mergeProjectQueryResults(client, 10)
    val projectResponse = client.query(DeclarativeGetProjectQuery()).fetchPolicy(FetchPolicy.CacheOnly).execute()
    assertTrue(projectResponse.data?.project?.name == "Stardust")

    // Store records 32 second ago, less than max age for Node: should cache miss
    mergeProjectQueryResults(client, 32)
    e = client.query(DeclarativeGetProjectQuery()).fetchPolicy(FetchPolicy.CacheOnly).execute().exception as CacheMissException
    assertTrue(e.stale)
  }

  private suspend fun mergeDeclarativeCompanyQueryResults(client: ApolloClient, secondsAgo: Int = 0) {
    val data = DeclarativeGetCompanyQuery.Data(DeclarativeGetCompanyQuery.Company(__typename = "Company", id = "42"))
    val records = data.normalized(DeclarativeGetCompanyQuery()).values
    client.apolloStore.accessCache {
      it.merge(records, receivedDate(currentTimeSeconds() - secondsAgo), DefaultRecordMerger)
    }
  }

  private suspend fun mergeUserQueryResults(client: ApolloClient, secondsAgo: Int = 0) {
    val data =
      DeclarativeGetUserQuery.Data(DeclarativeGetUserQuery.User(__typename = "User", name = "John", email = "john@doe.com", admin = true))
    val records = data.normalized(DeclarativeGetUserQuery()).values
    client.apolloStore.accessCache {
      it.merge(records, receivedDate(currentTimeSeconds() - secondsAgo), DefaultRecordMerger)
    }
  }

  private suspend fun mergeProjectQueryResults(client: ApolloClient, secondsAgo: Int = 0) {
    val data = DeclarativeGetProjectQuery.Data(DeclarativeGetProjectQuery.Project(__typename = "Project", id = "42", name = "Stardust"))
    val records = data.normalized(DeclarativeGetProjectQuery()).values
    client.apolloStore.accessCache {
      it.merge(records, receivedDate(currentTimeSeconds() - secondsAgo), DefaultRecordMerger)
    }
  }

  private fun configureClock(normalizedCacheFactory: NormalizedCacheFactory) = runTest {
    var delaySeconds = 0
    val client = ApolloClient.Builder()
        .cache(
            normalizedCacheFactory = normalizedCacheFactory,
            defaultMaxAge = 20.seconds,
        )
        .serverUrl("unused")
        .clock { currentTimeMillis() + delaySeconds * 1000 }
        .build()
    client.apolloStore.clearAll()

    // Read 25 seconds after write, more than default max age: should cache miss
    mergeDeclarativeCompanyQueryResults(client)
    delaySeconds = 25
    var e = client.query(DeclarativeGetCompanyQuery()).fetchPolicy(FetchPolicy.CacheOnly).execute().exception as CacheMissException
    assertTrue(e.stale)

    // Read 15 seconds after write, less than default max age: should not cache miss
    mergeDeclarativeCompanyQueryResults(client)
    delaySeconds = 15
    // Company fields are not configured so the default max age should be used
    val companyResponse = client.query(DeclarativeGetCompanyQuery()).fetchPolicy(FetchPolicy.CacheOnly).execute()
    assertTrue(companyResponse.data?.company?.id == "42")

    // Read 15 seconds after write, more than max age for User: should cache miss
    mergeUserQueryResults(client)
    delaySeconds = 15
    e = client.query(DeclarativeGetUserAdminQuery()).fetchPolicy(FetchPolicy.CacheOnly).execute().exception as CacheMissException
    assertTrue(e.stale)

    // Read 5 seconds after write, less than max age for User: should not cache miss
    mergeUserQueryResults(client)
    delaySeconds = 5
    val userAdminResponse = client.query(DeclarativeGetUserAdminQuery()).fetchPolicy(FetchPolicy.CacheOnly).execute()
    assertTrue(userAdminResponse.data?.user?.admin == true)

    // Read 10 seconds after write, more than max age for User.name: should cache miss
    mergeUserQueryResults(client)
    delaySeconds = 10
    e = client.query(DeclarativeGetUserNameQuery()).fetchPolicy(FetchPolicy.CacheOnly).execute().exception as CacheMissException
    assertTrue(e.stale)

    // Read 2 seconds after write, less than max age for User.name: should not cache miss
    mergeUserQueryResults(client)
    delaySeconds = 2
    val userNameResponse = client.query(DeclarativeGetUserNameQuery()).fetchPolicy(FetchPolicy.CacheOnly).execute()
    assertTrue(userNameResponse.data?.user?.name == "John")

    // Read 5 seconds after write, more than max age for User.email: should cache miss
    mergeUserQueryResults(client)
    delaySeconds = 5
    e = client.query(DeclarativeGetUserEmailQuery()).fetchPolicy(FetchPolicy.CacheOnly).execute().exception as CacheMissException
    assertTrue(e.stale)

    // Read 1 second after write, less than max age for User.email: should not cache miss
    mergeUserQueryResults(client)
    delaySeconds = 1
    val userEmailResponse = client.query(DeclarativeGetUserEmailQuery()).fetchPolicy(FetchPolicy.CacheOnly).execute()
    assertTrue(userEmailResponse.data?.user?.email == "john@doe.com")

    // Read 10 second after write, less that max age for Node: should not cache miss
    mergeProjectQueryResults(client)
    delaySeconds = 10
    val projectResponse = client.query(DeclarativeGetProjectQuery()).fetchPolicy(FetchPolicy.CacheOnly).execute()
    assertTrue(projectResponse.data?.project?.name == "Stardust")

    // Read 32 second after write, less than max age for Node: should cache miss
    mergeProjectQueryResults(client)
    delaySeconds = 32
    e = client.query(DeclarativeGetProjectQuery()).fetchPolicy(FetchPolicy.CacheOnly).execute().exception as CacheMissException
    assertTrue(e.stale)
  }
}

fun currentTimeSeconds() = currentTimeMillis() / 1000

fun receivedDate(receivedDateSeconds: Long): CacheHeaders {
  return CacheHeaders.Builder().addHeader(ApolloCacheHeaders.RECEIVED_DATE, receivedDateSeconds.toString()).build()
}
