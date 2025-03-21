package com.apollographql.cache.normalized.sql.internal

import app.cash.sqldelight.Query
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.apollographql.apollo.api.json.ApolloJsonElement
import com.apollographql.cache.normalized.api.ApolloCacheHeaders
import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.api.Record
import com.apollographql.cache.normalized.api.expirationDate
import com.apollographql.cache.normalized.api.receivedDate
import com.apollographql.cache.normalized.sql.internal.fields.Field_
import com.apollographql.cache.normalized.sql.internal.fields.FieldsDatabase
import com.apollographql.cache.normalized.sql.internal.fields.FieldsQueries
import okio.ByteString
import okio.ByteString.Companion.toByteString

internal class RecordDatabase(private val driver: SqlDriver) {
  private val fieldsQueries: FieldsQueries = FieldsDatabase(driver).fieldsQueries

  fun <T> transaction(body: () -> T): T {
    return fieldsQueries.transactionWithResult {
      body()
    }
  }

  /**
   * @param keys the keys of the records to select, size must be <= 999
   */
  fun selectRecords(keys: Collection<ByteArray>): List<Record> {
    val fieldsByRecordKey: Map<ByteString, List<Field_>> =
      fieldsQueries.selectRecords(keys).executeAsList().groupBy { it.record.toByteString() }
    return fieldsByRecordKey.toRecords()
  }

  fun selectAllRecords(): List<Record> {
    val fieldsByRecordKey: Map<ByteString, List<Field_>> =
      fieldsQueries.selectAllRecords().executeAsList().groupBy { it.record.toByteString() }
    return fieldsByRecordKey.toRecords()
  }

  private fun Map<ByteString, List<Field_>>.toRecords(): List<Record> =
    mapValues { (key, fieldList) ->
      val fieldValues: Map<String, ApolloJsonElement> = fieldList.associate { field ->
        field.field_ to ApolloJsonElementSerializer.deserialize(field.value_)
      }

      @Suppress("UNCHECKED_CAST")
      val metadata: Map<String, Map<String, ApolloJsonElement>> = fieldList.associate { field ->
        val deserializedMetadata = ApolloJsonElementSerializer.deserialize(field.metadata) as Map<String, ApolloJsonElement>?
        field.field_ to LinkedHashMap<String, ApolloJsonElement>((deserializedMetadata?.size ?: 0) + 2).also {
          if (deserializedMetadata != null) {
            it.putAll(deserializedMetadata)
          }

          // Dates are stored separately in their own columns
          if (field.received_date != null) {
            it.put(ApolloCacheHeaders.RECEIVED_DATE, field.received_date)
          }
          if (field.expiration_date != null) {
            it.put(ApolloCacheHeaders.EXPIRATION_DATE, field.expiration_date)
          }
        }
      }.filterValues { it.isNotEmpty() }
      Record(
          key = CacheKey(key),
          fields = fieldValues,
          metadata = metadata,
      )
    }.values.toList()

  fun insertOrUpdateRecord(record: Record) {
    for ((field, value) in record.fields) {
      insertOrUpdateField(
          record = record.key.key.toByteArray(),
          field = field,
          value = value,
          metadata = record.metadata[field],
          receivedDate = record.receivedDate(field),
          expirationDate = record.expirationDate(field),
      )
    }
  }

  private fun insertOrUpdateField(
      record: ByteArray,
      field: String,
      value: ApolloJsonElement,
      metadata: Map<String, ApolloJsonElement>?,
      receivedDate: Long?,
      expirationDate: Long?,
  ) {
    fieldsQueries.insertOrUpdateField(
        record = record,
        field_ = field,
        value_ = ApolloJsonElementSerializer.serialize(value),
        metadata = metadata
            ?.takeIf { it.isNotEmpty() }
            ?.let {
              ApolloJsonElementSerializer.serialize(
                  // Don't store the dates in the metadata as they are stored separately in their own columns
                  it - ApolloCacheHeaders.RECEIVED_DATE - ApolloCacheHeaders.EXPIRATION_DATE
              )
            },
        received_date = receivedDate,
        expiration_date = expirationDate,
    )
  }

  /**
   * @param keys the keys of the records to delete, size must be <= 999
   */
  fun deleteRecords(keys: Collection<ByteArray>) {
    fieldsQueries.deleteRecords(keys)
  }

  fun deleteAllRecords() {
    fieldsQueries.deleteAllRecords()
  }

  fun databaseSize(): Long {
    return driver.executeQuery(null, "SELECT page_count * page_size FROM pragma_page_count(), pragma_page_size();", {
      it.next()
      QueryResult.Value(it.getLong(0)!!)
    }, 0).value
  }

  fun count(): Query<Long> {
    return fieldsQueries.count()
  }

  fun trimByReceivedDate(limit: Long) {
    fieldsQueries.trimByReceivedDate(limit)
  }

  fun vacuum() {
    driver.execute(null, "VACUUM", 0)
  }

  fun changes(): Long {
    return fieldsQueries.changes().executeAsOne()
  }
}
