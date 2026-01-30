package test

import com.apollographql.apollo.api.Adapter
import com.apollographql.apollo.api.CustomScalarAdapters
import com.apollographql.apollo.api.json.JsonReader
import com.apollographql.apollo.api.json.JsonWriter

data class Point(
    val x: Int,
    val y: Int,
)

object PointAdapter : Adapter<Point> {
  override fun fromJson(reader: JsonReader, customScalarAdapters: CustomScalarAdapters): Point {
    var x: Int? = null
    var y: Int? = null
    reader.beginObject()
    while (reader.hasNext()) {
      when (reader.nextName()) {
        "x" -> x = reader.nextInt()
        "y" -> y = reader.nextInt()
        else -> reader.skipValue()
      }
    }
    reader.endObject()
    return Point(
        x = x!!,
        y = y!!,
    )
  }

  override fun toJson(writer: JsonWriter, customScalarAdapters: CustomScalarAdapters, value: Point) {
    writer.beginObject()
    writer.name("x").value(value.x)
    writer.name("y").value(value.y)
    writer.endObject()
  }
}
