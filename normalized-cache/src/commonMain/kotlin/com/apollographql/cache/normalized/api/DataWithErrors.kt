package com.apollographql.cache.normalized.api

import com.apollographql.apollo.api.CompiledField
import com.apollographql.apollo.api.CompiledFragment
import com.apollographql.apollo.api.CompiledListType
import com.apollographql.apollo.api.CompiledNotNullType
import com.apollographql.apollo.api.CompiledSelection
import com.apollographql.apollo.api.CustomScalarAdapters
import com.apollographql.apollo.api.Error
import com.apollographql.apollo.api.Executable
import com.apollographql.apollo.api.json.ApolloJsonElement
import com.apollographql.apollo.api.json.MapJsonWriter
import com.apollographql.cache.normalized.options.CacheOnError

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
  return (writer.root() as Map<String, ApolloJsonElement>).withErrors(errors)
}

/**
 * Returns this data with the given [errors] inlined.
 */
internal fun Map<String, ApolloJsonElement>.withErrors(errors: List<Error>?): DataWithErrors {
  if (errors.isNullOrEmpty()) return this
  var dataWithErrors = this
  for (error in errors) {
    val path = error.path ?: continue
    dataWithErrors = dataWithErrors.withErrorAt(path, error)
  }
  return dataWithErrors
}

@Suppress("UNCHECKED_CAST")
private fun Map<String, ApolloJsonElement>.withErrorAt(path: List<Any>, error: Error): DataWithErrors {
  var node: Any? = this.toMutableMap()
  val root = node as DataWithErrors
  for ((i, pathElement) in path.withIndex()) {
    when (pathElement) {
      is String -> {
        node as? MutableMap<String, Any?> ?: return root // Wrong info in path: give up
        if (i == path.lastIndex) {
          node[pathElement] = error
        } else {
          when (val v = node[pathElement]) {
            is Map<*, *> -> {
              node[pathElement] = v.toMutableMap()
            }

            is List<*> -> {
              node[pathElement] = v.toMutableList()
            }

            else -> {
              node[pathElement] = error
              break
            }
          }
        }
        node = node[pathElement]!!
      }

      is Int -> {
        node as? MutableList<Any?> ?: return root // Wrong info in path: give up
        if (pathElement !in node.indices) {
          // Wrong info in path: give up
          return root
        }
        if (i == path.lastIndex) {
          node[pathElement] = error
        } else {
          when (val v = node[pathElement]) {
            is Map<*, *> -> {
              node[pathElement] = v.toMutableMap()
            }

            is List<*> -> {
              node[pathElement] = v.toMutableList()
            }

            else -> {
              node[pathElement] = error
              break
            }
          }
        }
        node = node[pathElement]!!
      }

      else -> {
        // Wrong info in path: give up
        return root
      }
    }
  }
  return root
}

/**
 * If a position contains an Error, replace it by a null if the field's type is nullable, propagate the error if not.
 */
internal fun processErrors(
    dataWithErrors: Any?,
    field: CompiledField,
    onError: CacheOnError,
    errors: MutableList<Error>,
): ApolloJsonElement {
  return when (dataWithErrors) {
    is Map<*, *> -> {
      if (field.selections.isEmpty()) {
        // This is a scalar represented as a Map.
        return dataWithErrors
      }
      @Suppress("UNCHECKED_CAST")
      dataWithErrors as Map<String, Any?>
      val fieldSelections = field.fieldSelections()
      dataWithErrors.mapValues { (key, value) ->
        val selection = fieldSelections[key]
            ?: // Should never happen
            return@mapValues value
        when (value) {
          is Error -> {
            errors.add(value)
            if (onError == CacheOnError.HALT) {
              throw OnErrorHaltException()
            }
            if (onError == CacheOnError.PROPAGATE && selection.type is CompiledNotNullType) {
              return null
            }
            null
          }

          else -> {
            processErrors(value, selection, onError, errors).also {
              if (onError == CacheOnError.PROPAGATE && it == null && selection.type is CompiledNotNullType) {
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
            if (onError == CacheOnError.HALT) {
              throw OnErrorHaltException()
            }
            if (onError == CacheOnError.PROPAGATE && elementType is CompiledNotNullType) {
              return null
            }
            null
          }

          else -> {
            processErrors(value, field, onError, errors).also {
              if (onError == CacheOnError.PROPAGATE && it == null && elementType is CompiledNotNullType) {
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

internal class OnErrorHaltException : Exception("A field resolved to an error and OnError is set to HALT")

/**
 * Maps each response name this field selects to the selection it resolves to.
 *
 * Built once and reused for every key of the object: resolving one response name at a time re-flattens
 * the whole selection set per key, which is quadratic in the number of fields selected.
 */
private fun CompiledField.fieldSelections(): Map<String, CompiledField> {
  val fieldSelections = mutableMapOf<String, CompiledField>()

  fun collect(selections: List<CompiledSelection>) {
    for (selection in selections) {
      if (selection !is CompiledField) continue
      val existing = fieldSelections[selection.responseName]
      fieldSelections[selection.responseName] = if (existing == null) {
        selection
      } else {
        // Fields can be selected multiple times, combine the selections
        CompiledField.Builder(
            name = existing.name,
            type = existing.type,
        )
            .selections(existing.selections + selection.selections)
            .build()
      }
    }
    // Fragments are visited after the fields of the enclosing selection set, so that a field selected
    // both directly and in a fragment keeps the direct selection's name and type.
    for (selection in selections) {
      if (selection is CompiledFragment) {
        collect(selection.selections)
      }
    }
  }

  collect(selections)
  return fieldSelections
}
