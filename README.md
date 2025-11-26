<div align="center">

<p>
	<a href="https://www.apollographql.com/"><img src="https://raw.githubusercontent.com/apollographql/apollo-client-devtools/a7147d7db5e29b28224821bf238ba8e3a2fdf904/assets/apollo-wordmark.svg" height="100" alt="Apollo Client"></a>
</p>

[![Discourse](https://img.shields.io/discourse/topics?label=Discourse&server=https%3A%2F%2Fcommunity.apollographql.com&logo=discourse&color=467B95&style=flat-square)](http://community.apollographql.com/new-topic?category=Help&tags=mobile,client)
[![Slack](https://img.shields.io/static/v1?label=kotlinlang&message=apollo-kotlin&color=A97BFF&logo=slack&style=flat-square)](https://app.slack.com/client/T09229ZC6/C01A6KM1SBZ)

[![Maven Central](https://img.shields.io/maven-central/v/com.apollographql.cache/normalized-cache?style=flat-square)](https://central.sonatype.com/namespace/com.apollographql.cache)
[![Snapshots](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Fcentral.sonatype.com%2Frepository%2Fmaven-snapshots%2Fcom%2Fapollographql%2Fcache%2Fnormalized-cache%2Fmaven-metadata.xml&style=flat-square&label=snapshots&color=%2315252D&strategy=latestProperty)](https://central.sonatype.com/repository/maven-snapshots/com/apollographql/cache/normalized-cache/maven-metadata.xml)

</div>

## 🚀 Apollo Kotlin Normalized Cache

This repository hosts [Apollo Kotlin](https://github.com/apollographql/apollo-kotlin)'s new normalized cache, aiming to replace the main repository's version.

Compared to the previous version, this new normalized cache brings:

- [Pagination support](https://apollographql.github.io/apollo-kotlin-normalized-cache/pagination-home.html)
- [Expiration](https://apollographql.github.io/apollo-kotlin-normalized-cache/expiration.html) (a.k.a. Time to live)
- [Garbage collection](https://apollographql.github.io/apollo-kotlin-normalized-cache/garbage-collection.html), and [trimming](https://apollographql.github.io/apollo-kotlin-normalized-cache/trimming.html)
- [Partial results from the cache](https://apollographql.github.io/apollo-kotlin-normalized-cache/partial-cache-reads.html)
- API simplifications
- Key scope support
- SQL cache improved performance

## 📚 Documentation

See the project website for documentation:<br/>
[https://apollographql.github.io/apollo-kotlin-normalized-cache/](https://apollographql.github.io/apollo-kotlin-normalized-cache/)

The Kdoc API reference can be found at:<br/>
[https://apollographql.github.io/apollo-kotlin-normalized-cache/kdoc](https://apollographql.github.io/apollo-kotlin-normalized-cache/kdoc)

## ⚠️ Disclaimer

During the beta phase, the API is still subject to change, although we will try to make changes in non-breaking ways.

For now it is recommended to experiment with this library in non-critical projects/modules, or behind a feature flag.
