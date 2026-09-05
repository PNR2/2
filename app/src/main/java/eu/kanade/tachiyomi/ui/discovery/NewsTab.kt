@file:Suppress("ktlint:standard:max-line-length")

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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.TabOptions
import coil3.compose.AsyncImage
import eu.kanade.tachiyomi.data.discovery.DiscoveryProgressState
import eu.kanade.tachiyomi.data.discovery.DiscoverySort
import eu.kanade.tachiyomi.data.discovery.DiscoverySyncer
import eu.kanade.tachiyomi.data.discovery.MalDiscoveryItem
import eu.kanade.tachiyomi.data.discovery.MalDiscoveryRepository
import eu.kanade.tachiyomi.data.discovery.MergedManga
import eu.kanade.tachiyomi.data.discovery.MergedMangaRepository
import eu.kanade.tachiyomi.data.discovery.RssNewsItem
import eu.kanade.tachiyomi.data.discovery.RssNewsRepository
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
        val mergedRepo = remember { MergedMangaRepository() }
        val coroutineScope = rememberCoroutineScope()

        val articles by rssRepo.subscribeToNews().collectAsState(initial = emptyList())
        val seasonalManga by malRepo.subscribeToSeasonalManga().collectAsState(initial = emptyList())
        val mergedManga by mergedRepo.subscribeToMergedManga().collectAsState(initial = emptyList())
        val syncProgress by DiscoveryProgressState.progress.collectAsState()

        var selectedTabIndex by remember { mutableIntStateOf(0) }
        val tabs = listOf("News", "Seasonal", "Merged")

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
                                            Text(
                                                if (isAutomationOn) "Turn Off Auto-Link" else "Turn On Auto-Link",
                                            )
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

                            IconButton(
                                enabled = !syncProgress.isRunning,
                                onClick = {
                                    coroutineScope.launch(Dispatchers.IO) {
                                        DiscoverySyncer.syncNow()
                                    }
                                },
                            ) {
                                Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
                            }
                        },
                    )

                    if (syncProgress.isRunning) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                        ) {
                            LinearProgressIndicator(
                                progress = { syncProgress.percentage / 100f },
                                modifier = Modifier.fillMaxWidth().height(6.dp),
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${syncProgress.percentage}% - ${syncProgress.message}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }

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
                when (selectedTabIndex) {
                    0 -> NewsList(articles = articles)
                    1 -> SeasonalGrid(mangaList = seasonalManga)
                    2 -> MergedList(mergedList = mergedManga)
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
                        title = manga.title,
                        coverUrl = manga.coverUrl,
                        score = manga.score,
                        onClick = {
                            navigator.push(GlobalSearchScreen(manga.title))
                        },
                    )
                }
            }
        }
    }

    @Composable
    private fun MergedList(mergedList: List<MergedManga>) {
        val navigator = LocalNavigator.currentOrThrow

        if (mergedList.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No merged manga yet.\nRefresh Seasonal to create some.",
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
                items(mergedList) { manga ->
                    MangaCard(
                        title = manga.title,
                        coverUrl = manga.coverUrl,
                        score = null,
                        onClick = {
                            navigator.push(MergedMangaScreen(mergedId = manga.id))
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
    private fun MangaCard(
        title: String,
        coverUrl: String?,
        score: Double?,
        onClick: () -> Unit,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().clickable { onClick() },
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            Column {
                if (!coverUrl.isNullOrEmpty()) {
                    AsyncImage(
                        model = coverUrl,
                        contentDescription = "Manga Cover",
                        modifier = Modifier.fillMaxWidth().height(220.dp),
                        contentScale = ContentScale.Crop,
                    )
                }
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    if (score != null && score > 0.0) {
                        Text(
                            text = "⭐ $score",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}
