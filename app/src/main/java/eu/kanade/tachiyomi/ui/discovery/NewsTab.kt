package eu.kanade.tachiyomi.ui.discovery

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import coil3.compose.AsyncImage
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.data.discovery.RssNewsFetcher
import eu.kanade.tachiyomi.data.discovery.RssNewsItem
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The main UI screen for the RSS News feed.
 */
object NewsTab : Tab {

    override val options: TabOptions
        @Composable
        get() {
            val title = "News"
            val icon = rememberVectorPainter(Icons.Outlined.Article)

            return remember {
                TabOptions(
                    index = 10u, // Assigning a high index so it loads at the end of the bottom bar
                    title = title,
                    icon = icon,
                )
            }
        }

    override suspend fun onReselect(navigator: Navigator) {
        // TODO: Handle double-tap tab behavior later (like scrolling to the top of the news feed)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        // State variables to hold our data and loading status
        var articles by remember { mutableStateOf<List<RssNewsItem>>(emptyList()) }
        var isRefreshing by remember { mutableStateOf(true) }

        // The function that triggers the network fetch
        val fetchNews = {
            scope.launch {
                isRefreshing = true
                try {
                    val fetcher = RssNewsFetcher()
                    articles = fetcher.fetchNews(
                        "https://www.animenewsnetwork.com/news/rss.xml",
                        "Anime News Network",
                    )
                } finally {
                    isRefreshing = false
                }
            }
        }

        // Fetch the news immediately when the tab is opened
        LaunchedEffect(Unit) {
            fetchNews()
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Anime & Manga News") },
                    actions = {
                        IconButton(onClick = { fetchNews() }) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
                        }
                    },
                )
            },
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            ) {
                if (isRefreshing && articles.isEmpty()) {
                    // Show a loading spinner in the center if we have no data yet
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                } else {
                    // Display the list of articles
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(articles) { article ->
                            NewsCard(
                                article = article,
                                onClick = {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(article.link))
                                    context.startActivity(intent)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun NewsCard(article: RssNewsItem, onClick: () -> Unit) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() },
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            Column {
                // If there's an image, load it using Coil 3
                if (!article.imageUrl.isNullOrEmpty()) {
                    AsyncImage(
                        model = article.imageUrl,
                        contentDescription = "Article Thumbnail",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        contentScale = ContentScale.Crop,
                    )
                }

                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = article.sourceName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = article.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = article.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    val dateString = remember(article.publicationDate) {
                        val format = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
                        format.format(Date(article.publicationDate))
                    }
                    Text(
                        text = dateString,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}
