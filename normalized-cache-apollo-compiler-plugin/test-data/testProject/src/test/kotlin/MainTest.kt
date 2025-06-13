import com.example.cache.Cache
import kotlin.test.Test
import kotlin.test.assertEquals

class MainTest {
  @Test
  fun mainTest() {
    assertEquals("id", Cache.typePolicies.get("Product")?.keyFields?.single())
  }
}