package test

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.testing.QueueTestNetworkTransport
import com.apollographql.apollo.testing.enqueueTestResponse
import com.apollographql.cache.normalized.FetchPolicy
import com.apollographql.cache.normalized.api.TypePolicyCacheKeyGenerator
import com.apollographql.cache.normalized.fetchPolicy
import com.apollographql.cache.normalized.normalizedCache
import com.apollographql.cache.normalized.sql.SqlNormalizedCacheFactory
import com.apollographql.cache.normalized.testing.runTest
import okio.use
import test.cache.Cache
import test.fragment.Product
import test.type.Color
import kotlin.test.Test
import kotlin.test.assertEquals

class BrowserTest {
  @Test
  fun browserTest() = runTest {
    ApolloClient.Builder()
        .networkTransport(QueueTestNetworkTransport())
        .normalizedCache(
            normalizedCacheFactory = SqlNormalizedCacheFactory(),
            cacheKeyGenerator = TypePolicyCacheKeyGenerator(Cache.typePolicies)
        )
        .build()
        .use { apolloClient ->
          val getProductsQuery = GetProductsQuery()
          val productsData = GetProductsQuery.Data(
              products = listOf(
                  GetProductsQuery.Product(
                      __typename = "Product",
                      id = "1",
                      product = Product(
                          __typename = "Product",
                          id = "1",
                          name = "Product 1",
                          price = 10.0,
                          colors = listOf(
                              Product.Color(
                                  __typename = "StandardColor",
                                  onStandardColor = Product.OnStandardColor(Color.RED),
                                  onCustomColor = null,
                              ),
                          ),
                      )
                  ),
                  GetProductsQuery.Product(
                      __typename = "Product",
                      id = "2",
                      product = Product(
                          __typename = "Product",
                          id = "2",
                          name = "Product 2",
                          price = 20.0,
                          colors = listOf(
                              Product.Color(
                                  __typename = "CustomColor",
                                  onStandardColor = null,
                                  onCustomColor = Product.OnCustomColor(20, 120, 250),
                              ),
                          ),
                      ),
                  )
              ),
          )
          apolloClient.enqueueTestResponse(getProductsQuery, productsData)
          val networkResponse = apolloClient.query(getProductsQuery)
              .fetchPolicy(FetchPolicy.NetworkOnly)
              .execute()
          assertEquals(productsData, networkResponse.data)

          val cacheProductsResponse = apolloClient.query(getProductsQuery)
              .fetchPolicy(FetchPolicy.CacheOnly)
              .execute()
          assertEquals(productsData, cacheProductsResponse.data)

          val cacheProductResponse = apolloClient.query(GetProductQuery("2"))
              .fetchPolicy(FetchPolicy.CacheOnly)
              .execute()
          assertEquals(productsData.products!![1]!!.product, cacheProductResponse.data!!.product!!.product)
        }
  }
}
