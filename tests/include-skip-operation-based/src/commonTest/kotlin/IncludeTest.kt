
import com.apollographql.apollo.api.Optional
import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.internal.normalized
import com.apollographql.cache.normalized.testing.runTest
import com.example.GetCatIncludeVariableWithDefaultQuery
import com.example.SkipFragmentWithDefaultToFalseQuery
import com.example.type.buildCat
import com.example.type.buildDog
import kotlin.test.Test
import kotlin.test.assertNull

class IncludeTest {
  @Test
  fun getCatIncludeVariableWithDefaultQuery() = runTest {
    val operation = GetCatIncludeVariableWithDefaultQuery()

    val data = GetCatIncludeVariableWithDefaultQuery.Data {
      animal = buildCat {
        this["species"] = Optional.Absent
      }
    }

    val normalized = data.normalized(operation)
    assertNull((normalized[CacheKey("animal")] as Map<*, *>)["species"])
  }

  @Test
  fun skipFragmentWithDefaultToFalseQuery2() = runTest {
    val operation = SkipFragmentWithDefaultToFalseQuery()

    val data = SkipFragmentWithDefaultToFalseQuery.Data {
      animal = buildDog {
        barf = "ouaf"
      }
    }

    val normalized = data.normalized(operation)
    assertNull((normalized[CacheKey("animal")] as Map<*, *>)["barf"])
  }
}
