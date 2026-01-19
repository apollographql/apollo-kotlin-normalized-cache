package com.apollographql.cache.normalized.leveldb.internal

import com.apollographql.apollo.api.Error
import com.apollographql.apollo.api.Error.Builder
import com.apollographql.apollo.api.json.ApolloJsonElement
import com.apollographql.apollo.api.json.JsonNumber
import com.apollographql.cache.normalized.api.ApolloCacheHeaders
import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.api.Record
import com.apollographql.cache.normalized.api.RecordValue
import okio.Buffer
import okio.utf8Size

/**
 * A serializer that serializes/deserializes [RecordValue]s to/from [ByteArray]s.
 */
internal object RecordSerializer {
  fun serialize(record: Record): ByteArray {
    val buffer = Buffer()
    buffer.writeMap(record.fields)
    buffer._writeInt(record.metadata.size)
    for ((k, v) in record.metadata.mapKeys { (k, _) -> knownMetadataKeys[k] ?: k }) {
      buffer.writeString(k.shortenCacheKey())
      buffer.writeMap(v)
    }
    return buffer.readByteArray()
  }

  fun deserialize(key: String, bytes: ByteArray): Record {
    val buffer = Buffer().write(bytes)
    val fields = buffer.readMap()
    val metadataSize = buffer._readInt()
    val metadata = HashMap<String, Map<String, ApolloJsonElement>>(metadataSize).apply {
      repeat(metadataSize) {
        val k = buffer.readString().expandCacheKey()
        val v = buffer.readMap()
        put(k, v)
      }
    }.mapKeys { (k, _) -> knownMetadataKeysInverted[k] ?: k }
    return Record(
        key = CacheKey(key),
        fields = fields,
        mutationId = null,
        metadata = metadata,
    )
  }

  private fun Buffer.writeString(value: String) {
    _writeInt(value.utf8Size().toInt())
    writeUtf8(value)
  }

  private fun Buffer.readString(): String {
    return readUtf8(_readInt().toLong())
  }

  private fun Buffer._writeInt(value: Int) {
    when (value) {
      in 0..<FIRST -> {
        writeByte(value)
      }

      in Byte.MIN_VALUE..Byte.MAX_VALUE -> {
        writeByte(INT_BYTE)
        writeByte(value)
      }

      in Short.MIN_VALUE..Short.MAX_VALUE -> {
        writeByte(INT_SHORT)
        writeShort(value)
      }

      else -> {
        writeByte(INT_INT)
        writeInt(value)
      }
    }
  }

  private fun Buffer._readInt(): Int {
    val what = readByte().toInt() and 0xFF
    return when {
      what < FIRST -> what
      what == INT_BYTE -> readByte().toInt()
      what == INT_SHORT -> readShort().toInt()
      what == INT_INT -> readInt()
      else -> error("Trying to read unsupported Int type: $what")
    }
  }

  private fun Buffer._writeLong(value: Long) {
    when (value) {
      0L -> {
        writeByte(LONG_0)
      }

      in Byte.MIN_VALUE..Byte.MAX_VALUE -> {
        writeByte(LONG_BYTE)
        writeByte(value.toInt())
      }

      in Short.MIN_VALUE..Short.MAX_VALUE -> {
        writeByte(LONG_SHORT)
        writeShort(value.toInt())
      }

      in Int.MIN_VALUE..Int.MAX_VALUE -> {
        writeByte(LONG_INT)
        writeInt(value.toInt())
      }

      else -> {
        writeByte(LONG_LONG)
        writeLong(value)
      }
    }
  }

  private fun Buffer.writeMap(value: Map<*, *>) {
    _writeInt(value.size)
    @Suppress("UNCHECKED_CAST")
    value as Map<String, RecordValue>
    for ((k, v) in value) {
      writeString(k)
      writeAny(v)
    }
  }

  private fun Buffer.readMap(): Map<String, RecordValue> {
    val size = _readInt()
    return HashMap<String, RecordValue>(size).apply {
      repeat(size) {
        put(readString(), readAny())
      }
    }
  }

  private fun Buffer.writeAny(value: RecordValue) {
    when (value) {
      is String -> {
        if (value.isEmpty()) {
          writeByte(STRING_EMPTY)
        } else {
          writeByte(STRING)
          writeString(value)
        }
      }

      is Number -> {
        // Using `is` on numbers always return true in JS, so we use the class instead
        when (value::class) {
          Int::class -> _writeInt(value as Int)
          Long::class -> _writeLong(value as Long)
          else -> {
            writeByte(DOUBLE)
            writeLong((value as Double).toBits())
          }
        }
      }

      is JsonNumber -> {
        writeByte(JSON_NUMBER)
        writeString(value.value)
      }

      is Boolean -> {
        if (value) {
          writeByte(BOOLEAN_TRUE)
        } else {
          writeByte(BOOLEAN_FALSE)
        }
      }

      is CacheKey -> {
        writeByte(CACHE_KEY)
        writeString(value.key.shortenCacheKey())
      }

      is List<*> -> {
        if (value.isEmpty()) {
          writeByte(LIST_EMPTY)
        } else {
          writeByte(LIST)
          _writeInt(value.size)
          value.forEach {
            writeAny(it)
          }
        }
      }

      is Map<*, *> -> {
        if (value.isEmpty()) {
          writeByte(MAP_EMPTY)
        } else {
          writeByte(MAP)
          writeMap(value)
        }
      }

      null -> {
        writeByte(NULL)
      }

      is Error -> {
        writeByte(ERROR)
        writeString(value.message)
        _writeInt(value.locations?.size ?: 0)
        for (location in value.locations.orEmpty()) {
          _writeInt(location.line)
          _writeInt(location.column)
        }
        _writeInt(value.path?.size ?: 0)
        for (path in value.path.orEmpty()) {
          writeAny(path)
        }
        writeAny(value.extensions)
      }

      else -> error("Trying to write unsupported Record value: $value")
    }
  }

  private fun Buffer.readAny(): RecordValue {
    val what = readByte().toInt() and 0xFF
    return if (what < FIRST) {
      what
    } else {
      when (what) {
        STRING -> readString()
        STRING_EMPTY -> ""
        INT_BYTE -> readByte().toInt()
        INT_SHORT -> readShort().toInt()
        INT_INT -> readInt()
        LONG_0 -> 0L
        LONG_BYTE -> readByte().toLong()
        LONG_SHORT -> readShort().toLong()
        LONG_INT -> readInt().toLong()
        LONG_LONG -> readLong()
        DOUBLE -> Double.fromBits(readLong())
        JSON_NUMBER -> JsonNumber(readString())
        BOOLEAN_TRUE -> true
        BOOLEAN_FALSE -> false
        CACHE_KEY -> {
          CacheKey(readString().expandCacheKey())
        }

        LIST -> {
          val size = _readInt()
          0.until(size).map {
            readAny()
          }
        }

        LIST_EMPTY -> emptyList<RecordValue>()

        MAP -> {
          readMap()
        }

        MAP_EMPTY -> emptyMap<String, RecordValue>()

        NULL -> null

        ERROR -> {
          val message = readString()
          val locations = 0.until(_readInt()).map {
            Error.Location(_readInt(), _readInt())
          }
          val path = 0.until(_readInt()).map {
            readAny()!!
          }

          @Suppress("UNCHECKED_CAST")
          val extensions = readAny() as Map<String, Any?>?
          Builder(message = message)
              .path(path)
              .apply {
                for ((key, value) in extensions.orEmpty()) {
                  putExtension(key, value)
                }
                if (locations.isNotEmpty()) {
                  locations(locations)
                }
              }
              .build()
        }

        else -> error("Trying to read unsupported Record type: $what")
      }
    }
  }

  private const val FIRST = 255 - 32

  private const val NULL = FIRST
  private const val STRING = FIRST + 1
  private const val STRING_EMPTY = FIRST + 2
  private const val INT_BYTE = FIRST + 3
  private const val INT_SHORT = FIRST + 4
  private const val INT_INT = FIRST + 5
  private const val LONG_0 = FIRST + 6
  private const val LONG_BYTE = FIRST + 7
  private const val LONG_SHORT = FIRST + 8
  private const val LONG_INT = FIRST + 9
  private const val LONG_LONG = FIRST + 10
  private const val BOOLEAN_TRUE = FIRST + 11
  private const val BOOLEAN_FALSE = FIRST + 12
  private const val DOUBLE = FIRST + 13
  private const val JSON_NUMBER = FIRST + 14
  private const val LIST = FIRST + 15
  private const val LIST_EMPTY = FIRST + 16
  private const val MAP = FIRST + 17
  private const val MAP_EMPTY = FIRST + 18
  private const val CACHE_KEY = FIRST + 19
  private const val ERROR = FIRST + 20

  // Encode certain known metadata keys as single byte strings to save space
  private val knownMetadataKeys = mapOf(
      ApolloCacheHeaders.RECEIVED_DATE to "0",
      ApolloCacheHeaders.EXPIRATION_DATE to "1",
  )
  private val knownMetadataKeysInverted = knownMetadataKeys.entries.associate { (k, v) -> v to k }

  private val mutationPrefixLong = CacheKey.MUTATION_ROOT.key + "."
  private val subscriptionPrefixLong = CacheKey.SUBSCRIPTION_ROOT.key + "."

  // Use non printable characters to reduce likelihood of collisions with legitimate cache keys
  private const val mutationPrefixShort = "\u0001"
  private const val subscriptionPrefixShort = "\u0002"

  private fun String.shortenCacheKey(): String {
    return if (startsWith(mutationPrefixLong)) {
      replaceFirst(mutationPrefixLong, mutationPrefixShort)
    } else if (startsWith(subscriptionPrefixLong)) {
      replaceFirst(subscriptionPrefixLong, subscriptionPrefixShort)
    } else {
      this
    }
  }

  private fun String.expandCacheKey(): String {
    return if (startsWith(mutationPrefixShort)) {
      replaceFirst(mutationPrefixShort, mutationPrefixLong)
    } else if (startsWith(subscriptionPrefixShort)) {
      replaceFirst(subscriptionPrefixShort, subscriptionPrefixLong)
    } else {
      this
    }
  }
}
