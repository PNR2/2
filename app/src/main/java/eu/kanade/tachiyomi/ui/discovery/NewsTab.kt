@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.ui.discovery

import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.work.WorkManager
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.TabOptions
import coil3.compose.AsyncImage
import eu.kanade.tachiyomi.data.discovery.DiscoverySort
import eu.kanade.tachiyomi.data.discovery.DiscoverySyncWorker
import eu.kanade.tachiyomi.data.discovery.MalDiscoveryItem
import eu.kanade.tachiyomi.data.discovery.MalDiscoveryRepository
import eu.kanade.tachiyomi.data.discovery.RssNewsItem
import eu.kanade.tachiyomi.data.discovery.RssNewsRepository
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import eu.kanade.presentation.util.Tab as VoyagerTab

object NewsTab : VoyagerTab {

    override val options: TabOptions
        @Composable
        get() {
            val title = "Discovery"
            val icon = rememberVectorPainter(Icons.Outlined.Article)
            return remember { TabOptions(index = 10u, title = title, icon = icon) }
        }

    override suspend fun onReselect(navigator: Navigator) {}

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val rssRepo = remember { RssNewsRepository() }
        val malRepo = remember { MalDiscoveryRepository() }

        val articles by rssRepo.subscribeToNews().collectAsState(initial = emptyList())
        val seasonalManga by malRepo.subscribeToSeasonalManga().collectAsState(initial = emptyList())

        val workManager = remember { WorkManager.getInstance(context) }
        val workInfos by workManager.getWorkInfosByTagFlow(
            DiscoverySyncWorker.TAG,
        ).collectAsState(initial = emptyList())
        val isRefreshing = workInfos.any { it.state.isFinished.not() }

        var selectedTabIndex by remember { mutableIntStateOf(0) }
        val tabs = listOf("News", "Seasonal")

        var showMenu by remember { mutableStateOf(false) }
        var isAutomationOn by remember { mutableStateOf(MalDiscoveryRepository.isAutomationEnabled()) }

        Scaffold(
            topBar = {
                Column {
                    TopAppBar(
                        title = { Text("Discovery Hub") },
                        actions = {
                            Box {
                                IconButton(onClick = { showMenu = true }) {
                                    Icon(Icons.Outlined.FilterList, contentDescription = "Filter Options")
                                }
                                DropdownMenu(
                                    expanded = showMenu,
                                    onDismissRequest = { showMenu = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Sort by Latest") },
                                        onClick = {
                                            MalDiscoveryRepository.setSortMethod(DiscoverySort.LATEST)
                                            showMenu = false
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Sort by Score") },
                                        onClick = {
                                            MalDiscoveryRepository.setSortMethod(DiscoverySort.SCORE)
                                            showMenu = false
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Sort by Title") },
                                        onClick = {
                                            MalDiscoveryRepository.setSortMethod(DiscoverySort.TITLE)
                                            showMenu = false
                                        },
                                    )
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = {
                                            Text(if (isAutomationOn) "Turn Off Auto-Link" else "Turn On Auto-Link")
                                        },
                                        onClick = {
                                            val newState = !isAutomationOn
                                            MalDiscoveryRepository.setAutomationEnabled(newState)
                                            isAutomationOn = newState
                                            showMenu = false
                                        },
                                    )
                                }
                            }

                            if (isRefreshing) {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .padding(end = 16.dp, start = 8.dp)
                                        .size(24.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                IconButton(
                                    onClick = {
                                        Toast.makeText(
                                            context,
                                            "Searching for Seasonal Manga...",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                        DiscoverySyncWorker.startNow(context)
                                    },
                                ) {
                                    Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
                                }
                            }
                        },
                    )
                    TabRow(selectedTabIndex = selectedTabIndex) {
                        tabs.forEachIndexed { index, title ->
                            Tab(
                                selected = selectedTabIndex == index,
                                onClick = { selectedTabIndex = index },
                                text = { Text(title) },
                            )
                        }
                    }
                }
            },
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            ) {
                if (selectedTabIndex == 0) {
                    NewsList(articles = articles)
                } else {
                    SeasonalGrid(mangaList = seasonalManga)
                }
            }
        }
    }

    @Composable
    private fun NewsList(articles: List<RssNewsItem>) {
        val context = LocalContext.current
        if (articles.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Hit the refresh button to pull the latest news!",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
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

    @Composable
    private fun SeasonalGrid(mangaList: List<MalDiscoveryItem>) {
        val navigator = LocalNavigator.currentOrThrow

        if (mangaList.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Hit the refresh button to pull seasonal manga!",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(mangaList) { manga ->
                    MangaCard(
                        manga = manga,
                        onClick = {
                            if (manga.sourceId != null) {
                                navigator.push(GlobalSearchScreen(manga.title))
                            } else {
                                val url = "https://myanimelist.net/manga/${manga.malId}"
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                navigator.context.startActivity(intent)
                            }
                        },
                    )
                }
            }
        }
    }

    @Composable
    private fun NewsCard(article: RssNewsItem, onClick: () -> Unit) {
        Card(
            modifier = Modifier.fillMaxWidth().clickable { onClick() },
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            Column {
                if (!article.imageUrl.isNullOrEmpty()) {
                    AsyncImage(
                        model = article.imageUrl,
                        contentDescription = "Thumbnail",
                        modifier = Modifier.fillMaxWidth().height(180.dp),
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

    @Composable
    private fun MangaCard(manga: MalDiscoveryItem, onClick: () -> Unit) {
        Card(
            modifier = Modifier.fillMaxWidth().clickable { onClick() },
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            Column {
                if (!manga.coverUrl.isNullOrEmpty()) {
                    AsyncImage(
                        model = manga.coverUrl,
                        contentDescription = "Manga Cover",
                        modifier = Modifier.fillMaxWidth().height(220.dp),
                        contentScale = ContentScale.Crop,
                    )
                }
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = manga.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    if (manga.sourceId != null) {
                        Text(
                            text = "🔗 Linked to Extension",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    } else {
                        Text(
                            text = if (manga.score != null) "⭐ ${manga.score}" else "No Score",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}
