package com.example.browsersample

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import com.apollographql.apollo.api.ApolloResponse
import com.apollographql.apollo.exception.CacheMissException
import com.apollographql.cache.normalized.FetchPolicy
import com.apollographql.cache.normalized.fetchPolicy
import com.apollographql.cache.normalized.watch
import com.example.browsersample.graphql.RepositoryListQuery
import com.example.browsersample.graphql.fragment.RepositoryFields
import com.example.browsersample.repository.apolloClient
import com.example.browsersample.repository.fetchAndMergeNextPage
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Ul
import org.jetbrains.compose.web.renderComposable

class MainScreen {
  fun start() {
    val responseFlow = apolloClient.query(RepositoryListQuery())
        .watch()
        .filterNot { it.exception is CacheMissException }

    renderComposable(rootElementId = "root") {
      val response: ApolloResponse<RepositoryListQuery.Data>? by responseFlow.collectAsState(initial = null)
      if (response == null) {
        Text("Loading...")
      } else {
        RepositoryList(response!!)
      }
    }
  }
}

@Composable
private fun RepositoryList(response: ApolloResponse<RepositoryListQuery.Data>) {
  Ul {
    RefreshItem()
    response.data!!.organization!!.repositories.edges!!.map { it!!.node!!.repositoryFields }.forEach {
      RepositoryItem(it)
    }
    if (response.data!!.organization!!.repositories.pageInfo.hasNextPage) {
      LoadMoreItem()
    }
  }
}

@Composable
private fun RepositoryItem(repositoryFields: RepositoryFields) {
  Li {
    Div(
        attrs = {
          classes("repository-item-name-and-description")
        }
    ) {
      Div(
          attrs = {
            classes("repository-item-name")
          }
      ) {
        Text(repositoryFields.name)
      }
      Div {
        Text(repositoryFields.description.orEmpty())
      }
    }
    Div(
        attrs = {
          classes("repository-item-stars")
        }
    ) {
      Text(repositoryFields.stargazers.totalCount.toString() + " ☆")
    }
  }
}

@Composable
private fun RefreshItem() {
  val coroutineScope = rememberCoroutineScope()
  Button(
      attrs = {
        onClick {
          coroutineScope.launch {
            // Re-fetching the 1st page from the network will discard all other pages from the cache
            apolloClient.query(RepositoryListQuery())
                .fetchPolicy(FetchPolicy.NetworkOnly)
                .execute()
          }
        }
      }
  ) { Text("Refresh") }
}

@Composable
private fun LoadMoreItem() {
  val coroutineScope = rememberCoroutineScope()
  Button(
      attrs = {
        onClick {
          coroutineScope.launch { fetchAndMergeNextPage() }
        }
      }
  ) { Text("Load more...") }
}


// This is executed when index.html is opened.
fun main() {
  MainScreen().start()
}
