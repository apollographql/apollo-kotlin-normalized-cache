package test

import com.apollographql.apollo.api.Adapter
import com.apollographql.apollo.api.CustomScalarAdapters
import com.apollographql.apollo.api.json.JsonReader
import com.apollographql.apollo.api.json.JsonWriter

data class Category(
    val code: Int,
    val name: String,
)

val CategoryAdapter = object : Adapter<Category> {
  override fun fromJson(reader: JsonReader, customScalarAdapters: CustomScalarAdapters): Category {
    reader.beginObject()
    var code: Int? = null
    var name: String? = null
    while (true) {
      when (reader.selectName(listOf("code", "name"))) {
        0 -> code = reader.nextInt()
        1 -> name = reader.nextString()
        else -> break
      }
    }
    reader.endObject()
    return Category(code = code!!, name = name!!)
  }

  override fun toJson(writer: JsonWriter, customScalarAdapters: CustomScalarAdapters, value: Category) {
    writer.beginObject()
    writer.name("code").value(value.code)
    writer.name("name").value(value.name)
    writer.endObject()
  }
}
