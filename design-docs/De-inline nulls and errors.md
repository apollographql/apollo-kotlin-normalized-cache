# Proposal: de-inline nulls and errors in normalization

## Context: how it works today

When normalizing responses currently only JSON objects in positions corresponding to object/interface/union types are de-inlined and stored in their own records.
The value of the field in the parent record is then a reference (CacheKey) to that child record.

Other types of values (primitive types, nulls, errors, arrays, and JSON objects in positions corresponding to scalar types) are kept inline in their parent record or array.

Note: there is also a `@embedded` directive that can be used to force keeping objects inline.

For example, this response:

```json
{
  "data": {
    "user": {
      "id": "2",
      "firstName": "Jane",
      "lastName": "Doe",
      "company": null
    }
  }
}
```

is normalized as:

```json
{
  "query_root": {
    "user": "CacheKey(User:2)"
  },
  "User:2": {
    "id": "2",
    "firstName": "Jane",
    "lastName": "Doe",
    "company": null
  }
}
```

Another example with a list:

```json
{
  "data": {
    "users": [
      {
        "id": "1"
      },
      null,
      {
        "id": "3"
      }
    ]
  }
}
```

will be normalized as:

```json
{
  "query_root": {
    "users(ids: [1, 2, 3])": [
      "CacheKey(User:1)",
      null,
      "CacheKey(User:3)"
    ]
  },
  "User:1": {
    "id": "1"
  },
  "User:3": {
    "id": "3"
  }
}
```

## Problems with the current approach

Let's use the above example and assume there is also a field `user(id: ID!): User` in the schema, and that a field policy is configured.
If later a query requests the user with id "2":

- in the legacy cache, this would be resolved to `CacheKey(User:2)`, which doesn't exist in the cache, resulting in a cache miss.
  This is not desirable as the server returned value is `null`, and so that should be surfaced.
- in the modern cache, we implemented [a workaround](https://github.com/apollographql/apollo-kotlin-normalized-cache/blob/e126738e92c80184e1f9645b60abced486f0cf5a/normalized-cache/src/commonMain/kotlin/com/apollographql/cache/normalized/api/CacheResolver.kt#L157) where we look at existing list items in the record first before falling back to returning a cache key.

This works but is not ideal:

- only works if the list field and the item field are in the same record
- the code relies on the format of the field keys
- real use case from a user: deleting (or somehow omitting) the list field, to reduce the size of the record, should ideally not break the ability to resolve nulls/errors from the item field
- (subjective) keeping nulls/errors inline may be seen as inconsistent and surprising?

## Proposal

When normalizing responses, always de-inline parts of the response that correspond to objects/interfaces/unions in the schema, even if they are null or an error.

What cache key to use for these nulls/errors? We can't use the usual `CacheKeyGenerator` since there is no object to extract key fields from.

- If the parent field has arguments, and a configured field policy, we can use those to generate the cache key.
- Otherwise, use the path. In the example above, that would be `users.1` for the second item.

Normalizing the two examples above would result in:

```json
{
  "query_root": {
    "user": "CacheKey(User:2)"
  },
  "User:2": {
    "id": "2",
    "firstName": "Jane",
    "lastName": "Doe",
    "company": "CacheKey(User:2.company)"
  },
  "User:2.company": null
}
```

```json
{
  "query_root": {
    "users(ids: [1, 2, 3])": [
      "CacheKey(User:1)",
      "CacheKey(User:2)",
      "CacheKey(User:3)"
    ]
  },
  "User:1": {
    "id": "1"
  },
  "User:2": null,
  "User:3": {
    "id": "3"
  }
}
```

## Implications

- This would mean the store implementations and APIs need to support storing and returning/accepting nulls and errors in addition to records.
- In example 2, it appears we don't even need to store the list in the record? The resolver doesn't need it to return a list of cache keys.

## Open questions

What about arrays? Should they also be de-inlined?
