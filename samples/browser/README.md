# Browser sample

This samples demonstrates how to use the Apollo SQL normalized cache in a browser application.

It displays a list of repositories fetched from the [GitHub GraphQL API](https://docs.github.com/en/graphql).

Note: to execute the app, provide a [GitHub access token](https://developer.github.com/v4/guides/forming-calls/#authenticating-with-graphql) in the `gradle.properties` file.

This uses the [SQL.js](https://github.com/sql-js/sql.js/) library, with a custom Worker that loads/saves the database file via [OPFS](https://developer.mozilla.org/en-US/docs/Web/API/File_System_API/Origin_private_file_system).
