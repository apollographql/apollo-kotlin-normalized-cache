package test.declarativecache

import com.apollographql.cache.normalized.CacheManager
import com.apollographql.cache.normalized.api.CacheHeaders
import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.api.CacheResolver
import com.apollographql.cache.normalized.api.FieldPolicyCacheResolver
import com.apollographql.cache.normalized.api.ResolverContext
import com.apollographql.cache.normalized.api.TypePolicyCacheKeyGenerator
import com.apollographql.cache.normalized.memory.MemoryCacheFactory
import com.apollographql.cache.normalized.testing.runTest
import declarativecache.GetAuthorQuery
import declarativecache.GetBookQuery
import declarativecache.GetBooksQuery
import declarativecache.GetInterface3Query
import declarativecache.GetInterface5Query
import declarativecache.GetOtherBookQuery
import declarativecache.GetOtherLibraryQuery
import declarativecache.GetPromoAuthorQuery
import declarativecache.GetPromoBookQuery
import declarativecache.GetPromoLibraryQuery
import declarativecache.GetType2Query
import declarativecache.GetUnion1Query
import declarativecache.GetUnion2Query
import declarativecache.cache.Cache
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DeclarativeCacheTest {

  @Test
  fun typePolicyIsWorking() = runTest {
    val cacheManager = CacheManager(MemoryCacheFactory())

    // Write a book at the "promo" path
    val promoOperation = GetPromoBookQuery()
    val promoData = GetPromoBookQuery.Data(GetPromoBookQuery.PromoBook(title = "Promo", isbn = "42", __typename = "Book"))
    cacheManager.writeOperation(promoOperation, promoData)

    // Overwrite the book title through the "other" path
    val otherOperation = GetOtherBookQuery()
    val otherData = GetOtherBookQuery.Data(GetOtherBookQuery.OtherBook(isbn = "42", title = "Other", __typename = "Book"))
    cacheManager.writeOperation(otherOperation, otherData)

    // Get the "promo" book again, the title must be updated
    val data = cacheManager.readOperation(promoOperation).data!!

    assertEquals("Other", data.promoBook?.title)
  }

  @Test
  fun typePolicyWithAbstractTypes() = runTest {
    val cacheManager =
      CacheManager(MemoryCacheFactory(), cacheKeyGenerator = TypePolicyCacheKeyGenerator(Cache.typePolicies))

    val type2Data = GetType2Query.Data(GetType2Query.Type2(__typename = "Type2", type2Field = "type1Field", interface2KeyField = "42"))
    cacheManager.writeOperation(GetType2Query(), type2Data)
    val type2CacheKey = CacheKey("Type2", "42")
    val type2Record = cacheManager.accessCache { it.loadRecord(type2CacheKey, CacheHeaders.NONE) }
    assertNotNull(type2Record)

    cacheManager.clearAll()
    val union1Data =
      GetUnion1Query.Data(GetUnion1Query.Union1(__typename = "Type2", onType1 = null, onType2 = GetUnion1Query.OnType2(type2Field = "type2Field", interface2KeyField = "42")))
    cacheManager.writeOperation(GetUnion1Query(), union1Data)
    val union1Record = cacheManager.accessCache { it.loadRecord(type2CacheKey, CacheHeaders.NONE) }
    assertNotNull(union1Record)

    cacheManager.clearAll()
    val interface3Data =
      GetInterface3Query.Data(GetInterface3Query.Interface3(__typename = "Type4", interface3Field = "interface3Field", onType3 = null, onType4 = GetInterface3Query.OnType4(type4KeyField = "42")))
    cacheManager.writeOperation(GetInterface3Query(), interface3Data)
    val type4CacheKey = CacheKey("Type4", "42")
    val interface3Record = cacheManager.accessCache { it.loadRecord(type4CacheKey, CacheHeaders.NONE) }
    assertNotNull(interface3Record)

    cacheManager.clearAll()
    val union2Data =
      GetUnion2Query.Data(GetUnion2Query.Union2(__typename = "Type4", onType3 = null, onType4 = GetUnion2Query.OnType4(type4Field = "type4Field", type4KeyField = "42")))
    cacheManager.writeOperation(GetUnion2Query(), union2Data)
    val union2Record = cacheManager.accessCache { it.loadRecord(type4CacheKey, CacheHeaders.NONE) }
    assertNotNull(union2Record)

    cacheManager.clearAll()
    // An unknown type is returned, we fallback to the interface's key field
    val interface5Data =
      GetInterface5Query.Data(GetInterface5Query.Interface5(__typename = "UnknownType", interface5Field = "interface5Field", interface4KeyField = "42"))
    cacheManager.writeOperation(GetInterface5Query(), interface5Data)
    val interface5CacheKey = CacheKey("UnknownType", "42")
    val interface5Record = cacheManager.accessCache { it.loadRecord(interface5CacheKey, CacheHeaders.NONE) }
    assertNotNull(interface5Record)
  }

  @Test
  fun fallbackIdIsWorking() = runTest {
    val cacheManager = CacheManager(MemoryCacheFactory())

    // Write a library at the "promo" path
    val promoOperation = GetPromoLibraryQuery()
    val promoData = GetPromoLibraryQuery.Data(GetPromoLibraryQuery.PromoLibrary(address = "PromoAddress", id = "3", __typename = "Library"))
    cacheManager.writeOperation(promoOperation, promoData)

    // Overwrite the library address through the "other" path
    val otherOperation = GetOtherLibraryQuery()
    val otherData = GetOtherLibraryQuery.Data(GetOtherLibraryQuery.OtherLibrary(id = "3", address = "OtherAddress", __typename = "Library"))
    cacheManager.writeOperation(otherOperation, otherData)

    // Get the "promo" library again, the address must be updated
    val data = cacheManager.readOperation(promoOperation).data!!

    assertEquals("OtherAddress", data.promoLibrary?.address)
  }

  @Test
  fun fieldPolicyIsWorking() = runTest {
    val cacheManager = CacheManager(MemoryCacheFactory())

    val bookQuery1 = GetPromoBookQuery()
    val bookData1 = GetPromoBookQuery.Data(GetPromoBookQuery.PromoBook(title = "Promo", isbn = "42", __typename = "Book"))
    cacheManager.writeOperation(bookQuery1, bookData1)

    val bookQuery2 = GetBookQuery("42")
    val bookData2 = cacheManager.readOperation(bookQuery2).data!!

    assertEquals("Promo", bookData2.book?.title)

    val authorQuery1 = GetPromoAuthorQuery()
    val authorData1 = GetPromoAuthorQuery.Data(
        GetPromoAuthorQuery.PromoAuthor(
            firstName = "Pierre",
            lastName = "Bordage",
            __typename = "Author"
        )
    )

    cacheManager.writeOperation(authorQuery1, authorData1)

    val authorQuery2 = GetAuthorQuery("Pierre", "Bordage")
    val authorData2 = cacheManager.readOperation(authorQuery2).data!!

    assertEquals("Pierre", authorData2.author?.firstName)
    assertEquals("Bordage", authorData2.author?.lastName)
  }

  @Test
  fun fieldPolicyWithLists() = runTest {
    val cacheManager = CacheManager(MemoryCacheFactory())
    cacheManager.writeOperation(GetPromoBookQuery(), GetPromoBookQuery.Data(GetPromoBookQuery.PromoBook(title = "Promo", isbn = "42", __typename = "Book")))
    cacheManager.writeOperation(GetOtherBookQuery(), GetOtherBookQuery.Data(GetOtherBookQuery.OtherBook(isbn = "43", title = "Other Book", __typename = "Book")))

    val booksQuery = GetBooksQuery(listOf("42", "43"))
    val booksCacheResponse = cacheManager.readOperation(booksQuery)
    val booksData = booksCacheResponse.data!!
    assertEquals(2, booksData.books.size)
    assertEquals(GetBooksQuery.Book(__typename = "Book", title = "Promo", isbn = "42"), booksData.books[0])
    assertEquals(GetBooksQuery.Book(__typename = "Book", title = "Other Book", isbn = "43"), booksData.books[1])
  }

  @Test
  fun canResolveListProgrammatically() = runTest {
    val cacheResolver = object : CacheResolver {
      override fun resolveField(context: ResolverContext): Any? {
        val fieldName = context.field
        if (fieldName.name == "books") {
          @Suppress("UNCHECKED_CAST")
          val isbns = fieldName.argumentValue("isbns", context.variables).getOrThrow() as? List<String>
          if (isbns != null) {
            return isbns.map { CacheKey(fieldName.type.rawType().name, listOf(it)) }
          }
        }

        return FieldPolicyCacheResolver(keyScope = CacheKey.Scope.TYPE).resolveField(context)
      }
    }
    val cacheManager = CacheManager(MemoryCacheFactory(), cacheResolver = cacheResolver)

    val promoOperation = GetPromoBookQuery()
    cacheManager.writeOperation(promoOperation, GetPromoBookQuery.Data(GetPromoBookQuery.PromoBook(title = "Title1", isbn = "1", __typename = "Book")))
    cacheManager.writeOperation(promoOperation, GetPromoBookQuery.Data(GetPromoBookQuery.PromoBook(title = "Title2", isbn = "2", __typename = "Book")))
    cacheManager.writeOperation(promoOperation, GetPromoBookQuery.Data(GetPromoBookQuery.PromoBook(title = "Title3", isbn = "3", __typename = "Book")))
    cacheManager.writeOperation(promoOperation, GetPromoBookQuery.Data(GetPromoBookQuery.PromoBook(title = "Title4", isbn = "4", __typename = "Book")))

    var operation = GetBooksQuery(listOf("4", "1"))
    var data = cacheManager.readOperation(operation).data!!

    assertEquals("Title4", data.books.get(0).title)
    assertEquals("Title1", data.books.get(1).title)

    operation = GetBooksQuery(listOf("3"))
    data = cacheManager.readOperation(operation).data!!

    assertEquals("Title3", data.books.get(0).title)
  }
}
