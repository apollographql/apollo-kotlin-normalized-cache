# Pagination

The normalized cache includes support for pagination, allowing you to merge pages of data into the same record field.
This allows your application to watch a query for a list of items, receive updates when new pages are fetched, and update the UI with the full list.

- If your schema uses [Relay-style](https://relay.dev/graphql/connections.htm) pagination, the library [supports it](pagination-relay-style.md) out of the box.
- For other types of pagination, you can still use the pagination support, with more configuration needed.
- If you need more control, you can also [manually handle pagination](pagination-manual.md) using the `ApolloStore` APIs.

Keep reading to learn more about pagination in the context of a normalized cache.

## Pagination with a normalized cache

When using the normalized cache, objects are stored in records keyed by the object's id:

Query:

```graphql
query Users {
  allUsers(groupId: 2) {
    id
    name
  }
}
```

Response:

```json
{
  "data": {
    "allUsers": [
      {
        "id": 1,
        "name": "John Smith"
      },
      {
        "id": 2,
        "name": "Jane Doe"
      }
    ]
  }
}
```

Normalized cache:

| Cache Key  | Record                                            |
|------------|---------------------------------------------------|
| QUERY_ROOT | allUsers(groupId: 2): [ref(user:1), ref(user:2)]  | 
| user:1     | id: 1, name: John Smith                           |
| user:2     | id: 2, name: Jane Doe                             |

The app can watch the `Users()` query and update the UI with the whole list when the data changes.

However with pagination things become less obvious:

Query:

```graphql
query UsersPage($page: Int!) {
  usersPage(groupId: 2, page: $page) {
    id
    name
  }
}
```

Response:

```json
{
  "data": {
    "usersPage": [
      {
        "id": 1,
        "name": "John Smith"
      },
      {
        "id": 2,
        "name": "Jane Doe"
      }
    ]
  }
}
```

Normalized cache:

| Cache Key  | Record                                                     |
|------------|------------------------------------------------------------|
| QUERY_ROOT | usersPage(groupId: 2, page: 1): [ref(user:1), ref(user:2)] |
| user:1     | id: 1, name: John Smith                                    |
| user:2     | id: 2, name: Jane Doe                                      |

After fetching page 2, the cache will look like this:

| Cache Key  | Record                                                                                                                      |
|------------|-----------------------------------------------------------------------------------------------------------------------------|
| QUERY_ROOT | usersPage(groupId: 2, page: 1): [ref(user:1), ref(user:2)], <br/>usersPage(groupId: 2, page: 2): [ref(user:3), ref(user:4)] |
| user:1     | id: 1, name: John Smith                                                                                                     |
| user:2     | id: 2, name: Jane Doe                                                                                                       |
| user:3     | id: 3, name: Peter Parker                                                                                                   |
| user:4     | id: 4, name: Bruce Wayne                                                                                                    |

Which query should the app watch to update the UI?

Watching `UsersPage(page = 1)` would only notify changes to the first page.

For the whole list to be reactive you'd need to watch the queries for each page, and update the corresponding segment of the list. While technically possible, this is cumbersome to implement.

You could skip watching altogether and only update the list when scrolling to its end, but that would mean that changes to individual items would not be reflected in the list UI.

What we need is having the whole list in a single field, so we can watch a single query.
