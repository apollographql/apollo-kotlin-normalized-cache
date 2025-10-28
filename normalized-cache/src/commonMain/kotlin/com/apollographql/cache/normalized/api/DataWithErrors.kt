package com.apollographql.cache.normalized.api

import com.apollographql.apollo.api.CompiledField
import com.apollographql.apollo.api.CompiledFragment
import com.apollographql.apollo.api.CompiledListType
import com.apollographql.apollo.api.CompiledNotNullType
import com.apollographql.apollo.api.CompiledSelection
import com.apollographql.apollo.api.CustomScalarAdapters
import com.apollographql.apollo.api.Error
import com.apollographql.apollo.api.Executable
import com.apollographql.apollo.api.Operation
import com.apollographql.apollo.api.json.ApolloJsonElement
import com.apollographql.apollo.api.json.MapJsonWriter
import com.apollographql.apollo.exception.apolloExceptionHandler

/**
 * Encapsulates GraphQL data as a Map with inlined errors.
 * The values can be the same as [com.apollographql.apollo.api.json.ApolloJsonElement] with the addition of [Error].
 */
typealias DataWithErrors = Map<String, Any?>

/**
 * Returns this data as a Map with the given [errors] inlined.
 */
fun <D : Executable.Data> D.withErrors(
    executable: Executable<D>,
    errors: List<Error>?,
    customScalarAdapters: CustomScalarAdapters = CustomScalarAdapters.Empty,
): DataWithErrors {
  val writer = MapJsonWriter()
  executable.adapter().toJson(writer, customScalarAdapters, this)
  @Suppress("UNCHECKED_CAST")
  return (writer.root() as Map<String, ApolloJsonElement>).withErrors(errors, operationName = (executable as? Operation)?.name()
      ?: "Not an operation"
  )
}

/**
 * Returns this data with the given [errors] inlined.
 */
internal fun Map<String, ApolloJsonElement>.withErrors(errors: List<Error>?, operationName: String): DataWithErrors {
  if (errors == null || errors.isEmpty()) return this
  var dataWithErrors = this
  for (error in errors) {
    val path = error.path ?: continue
    dataWithErrors = try {
      dataWithErrors.withErrorAt(path, error)
    } catch (e: Exception) {
      // Could not inline error, log and ignore
      val message = "Could not inline error at path $path with message \"${error.message}\" for operation '$operationName'"
      apolloExceptionHandler(Exception(message, e))
      dataWithErrors
    }
  }
  return dataWithErrors
}


@Suppress("UNCHECKED_CAST")
private fun Map<String, ApolloJsonElement>.withErrorAt(path: List<Any>, error: Error): DataWithErrors {
  var node: Any? = this.toMutableMap()
  val root = node
  for ((i, key) in path.withIndex()) {
    if (key is String) {
      node as MutableMap<String, Any?>
      if (i == path.lastIndex) {
        node[key] = error
      } else {
        when (val v = node[key]) {
          is Map<*, *> -> {
            node[key] = v.toMutableMap()
          }

          is List<*> -> {
            node[key] = v.toMutableList()
          }

          else -> {
            node[key] = error
            break
          }
        }
      }
      node = node[key]!!
    } else {
      key as Int
      node as MutableList<Any?>
      if (i == path.lastIndex) {
        node[key] = error
      } else {
        when (val v = node[key]) {
          is Map<*, *> -> {
            node[key] = v.toMutableMap()
          }

          is List<*> -> {
            node[key] = v.toMutableList()
          }

          else -> {
            node[key] = error
            break
          }
        }
      }
      node = node[key]!!
    }
  }
  return root as Map<String, Any?>
}

/**
 * If a position contains an Error, replace it by a null if the field's type is nullable, propagate the error if not.
 */
internal fun propagateErrors(dataWithErrors: Any?, field: CompiledField, errors: MutableList<Error>): ApolloJsonElement {
  return when (dataWithErrors) {
    is Map<*, *> -> {
      if (field.selections.isEmpty()) {
        // This is a scalar represented as a Map.
        return dataWithErrors
      }
      @Suppress("UNCHECKED_CAST")
      dataWithErrors as Map<String, Any?>
      dataWithErrors.mapValues { (key, value) ->
        val selection = field.fieldSelection(key)
            ?: // Should never happen
            return@mapValues value
        when (value) {
          is Error -> {
            errors.add(value)
            if (selection.type is CompiledNotNullType) {
              return null
            }
            null
          }

          else -> {
            propagateErrors(value, selection, errors).also {
              if (it == null && selection.type is CompiledNotNullType) {
                return null
              }
            }
          }
        }
      }
    }

    is List<*> -> {
      val listType = if (field.type is CompiledNotNullType) {
        (field.type as CompiledNotNullType).ofType
      } else {
        field.type
      }
      if (listType !is CompiledListType) {
        // This is a scalar represented as a List.
        return dataWithErrors
      }
      dataWithErrors.map { value ->
        val elementType = listType.ofType
        when (value) {
          is Error -> {
            errors.add(value)
            if (elementType is CompiledNotNullType) {
              return null
            }
            null
          }

          else -> {
            propagateErrors(value, field, errors).also {
              if (it == null && elementType is CompiledNotNullType) {
                return null
              }
            }
          }
        }
      }
    }

    else -> {
      dataWithErrors
    }
  }
}

private fun CompiledSelection.fieldSelection(responseName: String): CompiledField? {
  fun CompiledSelection.fieldSelections(): List<CompiledField> {
    return when (this) {
      is CompiledField -> selections.filterIsInstance<CompiledField>() + selections.filterIsInstance<CompiledFragment>()
          .flatMap { it.fieldSelections() }

      is CompiledFragment -> selections.filterIsInstance<CompiledField>() + selections.filterIsInstance<CompiledFragment>()
          .flatMap { it.fieldSelections() }
    }
  }
  // Fields can be selected multiple times, combine the selections
  return fieldSelections().filter { it.responseName == responseName }.reduceOrNull { acc, compiledField ->
    CompiledField.Builder(
        name = acc.name,
        type = acc.type,
    )
        .selections(acc.selections + compiledField.selections)
        .build()
  }
}
