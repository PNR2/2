@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.ui.discovery

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import eu.kanade.tachiyomi.data.discovery.MalDiscoveryFetcher
import eu.kanade.tachiyomi.data.discovery.MalDiscoveryItem
import eu.kanade.tachiyomi.data.discovery.MalDiscoveryRepository
import eu.kanade.tachiyomi.data.discovery.MergedManga
import eu.kanade.tachiyomi.data.discovery.MergedMangaManager
import eu.kanade.tachiyomi.data.discovery.MergedMangaRepository
import eu.kanade.tachiyomi.data.discovery.RssNewsItem
import eu.kanade.tachiyomi.data.discovery.RssNewsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object NewsTab : eu.kanade.presentation.util.Tab {

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
        val malFetcher = remember { MalDiscoveryFetcher() }
        val coroutineScope = rememberCoroutineScope()
        val navigator = LocalNavigator.currentOrThrow

        val articles by rssRepo.subscribeToNews().collectAsState(initial = emptyList())
        val seasonalManga by malRepo.subscribeToSeasonalManga().collectAsState(initial = emptyList())
        val mergedManga by mergedRepo.subscribeToMergedManga().collectAsState(initial = emptyList())
        val syncProgress by DiscoveryProgressState.progress.collectAsState()

        var selectedTabIndex by remember { mutableIntStateOf(0) }
        val tabs = listOf("News", "Seasonal", "Merged")

        var showMenu by remember { mutableStateOf(false) }
        var isAutomationOn by remember { mutableStateOf(MalDiscoveryRepository.isAutomationEnabled()) }

        var openingMalId by remember { mutableLongStateOf(-1L) }
        var openingNewsKey by remember { mutableStateOf<String?>(null) }
        var openingStatus by remember { mutableStateOf("") }

        var filterYear by remember {
            mutableIntStateOf(MalDiscoveryFetcher.currentYear())
        }
        var filterMonth by remember {
            mutableIntStateOf(MalDiscoveryFetcher.currentMonth())
        }
        var seasonalLoading by remember { mutableStateOf(false) }
        var seasonalStatus by remember { mutableStateOf("") }
        var currentSort by remember { mutableStateOf(MalDiscoveryRepository.getSortMethod()) }

        fun openCohesiveFromTitle(
            title: String,
            coverUrl: String? = null,
            synopsis: String? = null,
            malId: Long? = null,
            progressKey: String,
        ) {
            if (openingNewsKey != null || openingMalId >= 0) return
            openingNewsKey = progressKey
            openingMalId = malId ?: -1L
            openingStatus = "Building cohesive entry for $title…"
            coroutineScope.launch {
                try {
                    val sourceManager = try {
                        Injekt.get<SourceManager>()
                    } catch (_: Exception) {
                        openingStatus = "SourceManager not available"
                        openingNewsKey = null
                        openingMalId = -1L
                        return@launch
                    }
                    val manager = MergedMangaManager(sourceManager)
                    val mergedId = withContext(Dispatchers.IO) {
                        manager.createOrUpdateMergedManga(
                            title = title,
                            coverUrl = coverUrl,
                            synopsis = synopsis,
                            author = null,
                            malId = malId,
                        )
                    }
                    openingNewsKey = null
                    openingMalId = -1L
                    openingStatus = ""
                    if (mergedId > 0) {
                        navigator.push(MergedMangaScreen(mergedId = mergedId))
                    } else {
                        openingStatus = "Could not create cohesive entry"
                    }
                } catch (e: Exception) {
                    openingNewsKey = null
                    openingMalId = -1L
                    openingStatus = "Error: ${e.message}"
                }
            }
        }

        fun applySeasonalFilter() {
            if (seasonalLoading) return
            seasonalLoading = true
            seasonalStatus = "Loading from MAL (Jikan)… this can take up to 1 min"
            coroutineScope.launch {
                try {
                    val year = filterYear.takeIf { it > 0 }
                    val month = filterMonth.takeIf { it in 1..12 }
                    val list = withContext(Dispatchers.IO) {
                        malFetcher.fetchSeasonalManga(year = year, month = month)
                    }
                    withContext(Dispatchers.IO) {
                        malRepo.insertSeasonalManga(list)
                    }
                    val realCount = list.count { it.malId > 0 }
                    seasonalStatus = when {
                        realCount == 0 -> "0 titles for this filter (or API empty)"
                        year == null && month == null -> "Loaded $realCount titles (any date)"
                        year != null && month == null -> "Loaded $realCount titles for $year"
                        else ->
                            "Loaded $realCount titles for " +
                                "${month.toString().padStart(2, '0')}/$year"
                    }
                } catch (e: Exception) {
                    seasonalStatus = "Error: ${e.message}"
                } finally {
                    seasonalLoading = false
                }
            }
        }

        fun applySort(sort: DiscoverySort) {
            currentSort = sort
            MalDiscoveryRepository.setSortMethod(sort)
        }

        Scaffold(
            topBar = {
                Column {
                    TopAppBar(
                        title = { Text("Discovery Hub") },
                        actions = {
                            Box {
                                IconButton(onClick = { showMenu = true }) {
                                    Icon(
                                        Icons.Outlined.FilterList,
                                        contentDescription = "Filter Options",
                                    )
                                }
                                DropdownMenu(
                                    expanded = showMenu,
                                    onDismissRequest = { showMenu = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Sort by Latest") },
                                        onClick = {
                                            applySort(DiscoverySort.LATEST)
                                            showMenu = false
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Sort by Score") },
                                        onClick = {
                                            applySort(DiscoverySort.SCORE)
                                            showMenu = false
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Sort by Chapters") },
                                        onClick = {
                                            applySort(DiscoverySort.CHAPTERS)
                                            showMenu = false
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Sort by Title") },
                                        onClick = {
                                            applySort(DiscoverySort.TITLE)
                                            showMenu = false
                                        },
                                    )
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                if (isAutomationOn) {
                                                    "Turn Off Auto-Link"
                                                } else {
                                                    "Turn On Auto-Link"
                                                },
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

                    if (openingStatus.isNotEmpty()) {
                        Text(
                            text = openingStatus,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
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
                    0 -> NewsList(
                        articles = articles,
                        openingKey = openingNewsKey,
                        onOpenArticle = { link ->
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link))
                            context.startActivity(intent)
                        },
                        onOpenCohesive = { article ->
                            openCohesiveFromTitle(
                                title = cleanNewsTitle(article.title),
                                synopsis = article.description,
                                progressKey = article.link,
                            )
                        },
                    )
                    1 -> SeasonalGrid(
                        mangaList = seasonalManga,
                        openingMalId = openingMalId,
                        filterYear = filterYear,
                        filterMonth = filterMonth,
                        currentSort = currentSort,
                        isLoading = seasonalLoading,
                        statusText = seasonalStatus,
                        onYearChange = { filterYear = it },
                        onMonthChange = { filterMonth = it },
                        onSortChange = { applySort(it) },
                        onApplyFilter = { applySeasonalFilter() },
                        onOpenCohesive = { item ->
                            openCohesiveFromTitle(
                                title = item.title,
                                coverUrl = item.coverUrl,
                                synopsis = item.synopsis,
                                malId = item.malId,
                                progressKey = "mal-${item.malId}",
                            )
                        },
                    )
                    2 -> MergedList(mergedList = mergedManga)
                }
            }
        }
    }

    private fun cleanNewsTitle(raw: String): String {
        var t = raw.trim()
        val prefixes = listOf(
            "Manga:",
            "Manga :",
            "New Manga:",
            "[Manga]",
            "(Manga)",
        )
        for (p in prefixes) {
            if (t.startsWith(p, ignoreCase = true)) {
                t = t.removePrefix(p).trim()
                break
            }
        }
        val cutters = listOf(" Announced", " Gets ", " Receives ", " Reveals ")
        for (c in cutters) {
            val idx = t.indexOf(c, ignoreCase = true)
            if (idx in 3..60) {
                t = t.substring(0, idx).trim()
                break
            }
        }
        return t.ifBlank { raw.trim() }
    }

    @Composable
    private fun NewsList(
        articles: List<RssNewsItem>,
        openingKey: String?,
        onOpenArticle: (String) -> Unit,
        onOpenCohesive: (RssNewsItem) -> Unit,
    ) {
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
                items(articles, key = { it.link }) { article ->
                    NewsCard(
                        article = article,
                        isOpening = openingKey == article.link,
                        onOpenArticle = { onOpenArticle(article.link) },
                        onOpenCohesive = { onOpenCohesive(article) },
                    )
                }
            }
        }
    }

    @Composable
    private fun SeasonalGrid(
        mangaList: List<MalDiscoveryItem>,
        openingMalId: Long,
        filterYear: Int,
        filterMonth: Int,
        currentSort: DiscoverySort,
        isLoading: Boolean,
        statusText: String,
        onYearChange: (Int) -> Unit,
        onMonthChange: (Int) -> Unit,
        onSortChange: (DiscoverySort) -> Unit,
        onApplyFilter: () -> Unit,
        onOpenCohesive: (MalDiscoveryItem) -> Unit,
    ) {
        val currentY = MalDiscoveryFetcher.currentYear()
        val years = listOf(0) + (currentY downTo currentY - 8).toList()
        val months = listOf(0) + (1..12).toList()

        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    text = "Sort (like MAL columns)",
                    style = MaterialTheme.typography.labelMedium,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = currentSort == DiscoverySort.SCORE,
                        onClick = { onSortChange(DiscoverySort.SCORE) },
                        label = { Text("Score") },
                    )
                    FilterChip(
                        selected = currentSort == DiscoverySort.CHAPTERS,
                        onClick = { onSortChange(DiscoverySort.CHAPTERS) },
                        label = { Text("Chapters") },
                    )
                    FilterChip(
                        selected = currentSort == DiscoverySort.LATEST,
                        onClick = { onSortChange(DiscoverySort.LATEST) },
                        label = { Text("Start date") },
                    )
                    FilterChip(
                        selected = currentSort == DiscoverySort.TITLE,
                        onClick = { onSortChange(DiscoverySort.TITLE) },
                        label = { Text("Title") },
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Month (0 = Any)",
                    style = MaterialTheme.typography.labelMedium,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    months.forEach { m ->
                        FilterChip(
                            selected = filterMonth == m,
                            onClick = { onMonthChange(m) },
                            label = {
                                Text(
                                    if (m == 0) "Any" else m.toString().padStart(2, '0'),
                                )
                            },
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Year (0 = Any)",
                    style = MaterialTheme.typography.labelMedium,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    years.forEach { y ->
                        FilterChip(
                            selected = filterYear == y,
                            onClick = { onYearChange(y) },
                            label = { Text(if (y == 0) "Any" else y.toString()) },
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onApplyFilter,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (isLoading) "Loading…" else "Apply filter")
                }

                if (statusText.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            if (mangaList.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Apply filter or hit hub Refresh for seasonal manga.",
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
                    items(mangaList, key = { it.malId }) { manga ->
                        MangaCard(
                            title = manga.title,
                            coverUrl = manga.coverUrl,
                            score = manga.score,
                            chapters = manga.chapters,
                            isLoading = openingMalId == manga.malId,
                            onClick = { onOpenCohesive(manga) },
                        )
                    }
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
                    text = "No merged manga yet.\nOpen Seasonal/News or use Browse Search.",
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
                items(mergedList, key = { it.id }) { manga ->
                    MangaCard(
                        title = manga.title,
                        coverUrl = manga.coverUrl,
                        score = null,
                        chapters = null,
                        isLoading = false,
                        onClick = {
                            navigator.push(MergedMangaScreen(mergedId = manga.id))
                        },
                    )
                }
            }
        }
    }

    @Composable
    private fun NewsCard(
        article: RssNewsItem,
        isOpening: Boolean,
        onOpenArticle: () -> Unit,
        onOpenCohesive: () -> Unit,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            Column {
                if (!article.imageUrl.isNullOrEmpty()) {
                    AsyncImage(
                        model = article.imageUrl,
                        contentDescription = "Thumbnail",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clickable { onOpenArticle() },
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
                        modifier = Modifier.clickable { onOpenArticle() },
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

                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        OutlinedButton(
                            onClick = onOpenArticle,
                            modifier = Modifier.weight(1f),
                            enabled = !isOpening,
                        ) {
                            Text("Read news")
                        }
                        Button(
                            onClick = onOpenCohesive,
                            modifier = Modifier.weight(1f),
                            enabled = !isOpening,
                        ) {
                            if (isOpening) {
                                CircularProgressIndicator(
                                    modifier = Modifier.height(18.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Text("Open cohesive")
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun MangaCard(
        title: String,
        coverUrl: String?,
        score: Double?,
        chapters: Int?,
        isLoading: Boolean,
        onClick: () -> Unit,
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !isLoading) { onClick() },
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
                    val meta = buildString {
                        if (score != null && score > 0.0) append("⭐ $score")
                        if (chapters != null && chapters > 0) {
                            if (isNotEmpty()) append(" · ")
                            append("$chapters ch")
                        }
                    }
                    if (meta.isNotEmpty()) {
                        Text(
                            text = meta,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (isLoading) {
                        Spacer(modifier = Modifier.height(8.dp))
                        CircularProgressIndicator(
                            modifier = Modifier
                                .height(22.dp)
                                .align(Alignment.CenterHorizontally),
                            strokeWidth = 2.dp,
                        )
                    }
                }
            }
        }
    }
}
