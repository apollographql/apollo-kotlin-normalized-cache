# Other types of pagination

If your schema doesn't use [Relay-style](https://relay.dev/graphql/connections.htm) pagination, you can still use the pagination support,
with more configuration needed.

#### Pagination arguments

Arguments that should be omitted from the field key can be specified programmatically by configuring your cache with a [`FieldKeyGenerator`](https://apollographql.github.io/apollo-kotlin-normalized-cache/kdoc/normalized-cache/com.apollographql.cache.normalized.api/-field-key-generator/index.html?query=interface%20FieldKeyGenerator) implementation:

```kotlin
object MyFieldKeyGenerator : FieldKeyGenerator {
  override fun getFieldKey(context: FieldKeyContext): String {
    return if (context.parentType == "Query" && context.field.name == "usersPage") {
      context.field.newBuilder()
          .arguments(
              context.field.arguments.filter { argument ->
                argument.definition.name != "page" // Omit the `page` argument from the field key
              }
          )
          .build()
          .nameWithArguments(context.variables)
    } else {
      DefaultFieldKeyGenerator.getFieldKey(context)
    }
  }
}
```

```kotlin
val client = ApolloClient.Builder()
    // ...
    .normalizedCache(
        normalizedCacheFactory = cacheFactory,
        fieldKeyGenerator = MyFieldKeyGenerator, // Configure the cache with the custom field key generator
    )
    .build()
```

With that in place, after fetching the first page, the cache will look like this:

| Cache Key  | Record                                                |
|------------|-------------------------------------------------------|
| QUERY_ROOT | **usersPage(groupId: 2)**: [ref(user:1), ref(user:2)] |
| user:1     | id: 1, name: John Smith                               |
| user:2     | id: 2, name: Jane Doe                                 |

The field key no longer includes the `page` argument, which means watching `UsersPage(page = 1)` or any page will observe the same list.

Here's what happens when fetching the second page:

| Cache Key  | Record                                            |
|------------|---------------------------------------------------|
| QUERY_ROOT | usersPage(groupId: 2): [ref(user:3), ref(user:4)] |
| user:1     | id: 1, name: John Smith                           |
| user:2     | id: 2, name: Jane Doe                             |
| user:3     | id: 3, name: Peter Parker                         |
| user:4     | id: 4, name: Bruce Wayne                          |

The field containing the first page was overwritten by the second page.

This is because the field key is now the same for all pages and the default merging strategy is to overwrite existing fields with the new value.

#### Record merging

To fix this, we need to supply the store with a piece of code that can merge the lists in a sensible way.
This is done by passing a [`RecordMerger`](https://apollographql.github.io/apollo-kotlin-normalized-cache/kdoc/normalized-cache/com.apollographql.cache.normalized.api/-record-merger/index.html?query=interface%20RecordMerger) when configuring your cache:

```kotlin
object MyFieldMerger : FieldRecordMerger.FieldMerger {
  override fun mergeFields(existing: FieldRecordMerger.FieldInfo, incoming: FieldRecordMerger.FieldInfo): FieldRecordMerger.FieldInfo {
    val existingList = existing.value as List<*>
    val incomingList = incoming.value as List<*>
    val mergedList = existingList + incomingList
    return FieldRecordMerger.FieldInfo(
        value = mergedList,
        metadata = emptyMap()
    )
  }
}

val client = ApolloClient.Builder()
  // ...
  .normalizedCache(
    normalizedCacheFactory = cacheFactory,
    recordMerger = FieldRecordMerger(MyFieldMerger), // Configure the store with the custom merger
  )
  .build()
```

With this, the cache will be as expected after fetching the second page:

| Cache Key  | Record                                                                      |
|------------|-----------------------------------------------------------------------------|
| QUERY_ROOT | usersPage(groupId: 2): [ref(user:1), ref(user:2), ref(user:3), ref(user:4)] |
| user:1     | id: 1, name: John Smith                                                     |
| user:2     | id: 2, name: Jane Doe                                                       |
| user:3     | id: 3, name: Peter Parker                                                   |
| user:4     | id: 4, name: Bruce Wayne                                                    |

The `RecordMerger` shown above is simplistic: it will always append new items to the end of the existing list.
In a real app, we need to look at the contents of the incoming page and decide if and where to append / insert the items.

To do that it is usually necessary to have access to the arguments that were used to fetch the existing/incoming lists (e.g. the page number),
to decide what to do with the new items.
For instance if the existing list is for page 1 and the incoming one is for page 2, we should append.

Fields in records can have arbitrary metadata attached to them, in addition to their value. We'll use this to implement a more capable merging strategy.

#### Metadata

Let's go back to the [example](pagination-relay-style.md) where Relay-style pagination is used.

Configure the `fieldKeyGenerator` as seen previously:

```kotlin
object MyFieldKeyGenerator : FieldKeyGenerator {
  override fun getFieldKey(context: FieldKeyContext): String {
    return if (context.field.type.rawType().name == "UserConnection") {
      context.field.newBuilder()
          .arguments(
              context.field.arguments.filter { argument ->
                argument.definition.name !in setOf("first", "after", "last", "before") // Omit pagination arguments from the field key
              }
          )
          .build()
          .nameWithArguments(context.variables)
    } else {
      DefaultFieldKeyGenerator.getFieldKey(context)
    }
  }
}
```

Now let's store in the metadata of each `UserConnection` field the values of the `before` and `after` arguments of the field returning it,
as well as the values of the first and last cursor in its list.
This will allow us to insert new pages in the correct position later on.

This is done by passing a [`MetadataGenerator`](https://apollographql.github.io/apollo-kotlin-normalized-cache/kdoc/normalized-cache/com.apollographql.cache.normalized.api/-metadata-generator/index.html?query=interface%20MetadataGenerator) when configuring the cache:

```kotlin
class ConnectionMetadataGenerator : MetadataGenerator {
  @Suppress("UNCHECKED_CAST")
  override fun metadataForObject(obj: ApolloJsonElement, context: MetadataGeneratorContext): Map<String, ApolloJsonElement> {
    if (context.field.type.rawType().name == "UserConnection") {
      obj as Map<String, ApolloJsonElement>
      val edges = obj["edges"] as List<Map<String, ApolloJsonElement>>
      val startCursor = edges.firstOrNull()?.get("cursor") as String?
      val endCursor = edges.lastOrNull()?.get("cursor") as String?
      return mapOf(
          "startCursor" to startCursor,
          "endCursor" to endCursor,
          "before" to context.argumentValue("before"),
          "after" to context.argumentValue("after"),
      )
    }
    return emptyMap()
  }
}
```

However, this cannot work yet.

Normalization will make the `usersConnection` field value be a **reference** to the `UserConnection` record, and not the actual connection.
Because of this, we won't be able to access its metadata inside the `RecordMerger` implementation.
Furthermore, the `edges` field value will be a list of **references** to the `UserEdge` records which will contain the item's list index in their
cache key (e.g. `usersConnection.edges.0`, `usersConnection.edges.1`) which will break the merging logic.

#### Embedded fields

To remediate this, we can configure the cache to skip normalization for certain fields. When doing so, the value will be embedded directly into
the record instead of being referenced.

This is done with the `embeddedFields` argument of the `@typePolicy` directive:

```graphql
# Embed the value of the `usersConnection` field in the record
extend type Query @typePolicy(embeddedFields: "usersConnection")

# Embed the values of the `edges` field in the record
extend type UserConnection @typePolicy(embeddedFields: "edges")
```

> This can also be done programmatically by configuring the cache with an [`EmbeddedFieldsProvider`](https://apollographql.github.io/apollo-kotlin-normalized-cache/kdoc/normalized-cache/com.apollographql.cache.normalized.api/-embedded-fields-provider/index.html?query=interface%20EmbeddedFieldsProvider) implementation.

Now that we have the metadata and embedded fields in place, we can implement the `RecordMerger` (simplified for brevity):

```kotlin
object ConnectionFieldMerger : FieldRecordMerger.FieldMerger {
  @Suppress("UNCHECKED_CAST")
  override fun mergeFields(existing: FieldRecordMerger.FieldInfo, incoming: FieldRecordMerger.FieldInfo): FieldRecordMerger.FieldInfo {
    // Get existing field metadata
    val existingStartCursor = existing.metadata["startCursor"]
    val existingEndCursor = existing.metadata["endCursor"]

    // Get incoming field metadata
    val incomingBeforeArgument = incoming.metadata["before"]
    val incomingAfterArgument = incoming.metadata["after"]

    // Get the lists
    val existingList = (existing.value as Map<String, ApolloJsonElement>)["edges"] as List<*>
    val incomingList = (incoming.value as Map<String, ApolloJsonElement>)["edges"] as List<*>

    // Merge the lists
    val mergedList: List<*> = if (incomingAfterArgument == existingEndCursor) {
      // We received the next page: its `after` argument matches the last cursor of the existing list
      existingList + incomingList
    } else if (incomingBeforeArgument == existingStartCursor) {
      // We received the previous page: its `before` argument matches the first cursor of the existing list
      incomingList + existingList
    } else {
      // We received a list which is neither the previous nor the next page.
      // Handle this case by resetting the cache with this page
      incomingList
    }

    val mergedFieldValue = existing.value.toMutableMap()
    mergedFieldValue["edges"] = mergedList
    return FieldRecordMerger.FieldInfo(
        value = mergedFieldValue,
        metadata = mapOf() // Omitted for brevity
    )
  }
}
```

A full implementation of `ConnectionFieldMerger` can be found [here](https://github.com/apollographql/apollo-kotlin-normalized-cache/blob/main/normalized-cache/src/commonMain/kotlin/com/apollographql/cache/normalized/api/RecordMerger.kt#L136).
