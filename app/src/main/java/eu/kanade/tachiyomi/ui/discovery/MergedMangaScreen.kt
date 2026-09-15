@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.ui.discovery

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.tachiyomi.data.discovery.MergedChapter
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.flow.collectLatest

data class MergedMangaScreen(
    val mergedId: Long,
) : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current

        val viewModel = assistedMetroViewModel<MergedMangaViewModel, MergedMangaViewModel.Factory> {
            create(mergedId = mergedId)
        }
        val state by viewModel.state.collectAsState()

        val manga = state.manga
        val title = manga?.title ?: "…"
        val synopsis = manga?.synopsis
        val covers = manga?.allCovers().orEmpty()
        val scanlation = manga?.scanlationGroups
        val author = manga?.author
        val artist = manga?.artist
        val genres = manga?.genres
        val status = manga?.status

        var synopsisExpanded by remember { mutableStateOf(false) }

        LaunchedEffect(viewModel) {
            viewModel.openReader.collectLatest { open ->
                val intent = ReaderActivity.newIntent(
                    context,
                    open.mangaId,
                    open.chapterId,
                )
                context.startActivity(intent)
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title, maxLines = 1) },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(
                                Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = "Back",
                            )
                        }
                    },
                )
            },
        ) { paddingValues ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    if (covers.isNotEmpty()) {
                        val pagerState = rememberPagerState(pageCount = { covers.size })
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(240.dp),
                        ) { page ->
                            AsyncImage(
                                model = covers[page],
                                contentDescription = "Cover ${page + 1}",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                        }
                        if (covers.size > 1) {
                            Text(
                                text = "Cover ${pagerState.currentPage + 1} / ${covers.size}  ·  swipe",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )

                    // Author / artist / status (Mihon-like info row)
                    val infoLine = buildString {
                        if (!author.isNullOrBlank()) append(author)
                        if (!artist.isNullOrBlank() && artist != author) {
                            if (isNotEmpty()) append(" · ")
                            append(artist)
                        }
                        if (!status.isNullOrBlank()) {
                            if (isNotEmpty()) append(" · ")
                            append(status)
                        }
                    }
                    if (infoLine.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = infoLine,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (!genres.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = genres,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    // Expandable synopsis (like Mihon)
                    if (!synopsis.isNullOrEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateContentSize()
                                .clickable { synopsisExpanded = !synopsisExpanded },
                        ) {
                            Text(
                                text = synopsis,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = if (synopsisExpanded) Int.MAX_VALUE else 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = if (synopsisExpanded) "Show less" else "Show more",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }

                    // Scanlation (vision: separate at bottom of header)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (!scanlation.isNullOrBlank()) {
                            "Scanlation: $scanlation"
                        } else {
                            "Scanlation: —"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = { viewModel.relink() },
                        enabled = !state.isRelinking &&
                            !state.isFetchingChapters &&
                            manga != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (state.isRelinking) "Linking…" else "Re-link sources")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = { viewModel.fetchChapters() },
                        enabled = !state.isFetchingChapters &&
                            !state.isRelinking &&
                            state.references.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (state.isFetchingChapters) {
                                "Fetching chapters…"
                            } else {
                                "Fetch chapters"
                            },
                        )
                    }

                    if (state.statusText.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = state.statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                if (state.allChapters.isNotEmpty()) {
                    item {
                        val label = when (state.languageFilter.lowercase()) {
                            "en", "eng", "english", "gb" -> "English"
                            "all" -> "All languages"
                            else -> state.languageFilter.uppercase()
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.toggleLanguagePanel() }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Translate,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = "  Language · $label",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                imageVector = if (state.languagePanelExpanded) {
                                    Icons.Outlined.KeyboardArrowUp
                                } else {
                                    Icons.Outlined.KeyboardArrowDown
                                },
                                contentDescription = null,
                            )
                        }

                        if (state.languagePanelExpanded) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                val filters = buildList {
                                    add("en")
                                    add("all")
                                    addAll(
                                        state.availableLanguages.filter {
                                            it != "en" && it != "all"
                                        },
                                    )
                                }.distinct()

                                filters.forEach { lang ->
                                    FilterChip(
                                        selected = state.languageFilter.equals(
                                            lang,
                                            ignoreCase = true,
                                        ),
                                        onClick = { viewModel.setLanguageFilter(lang) },
                                        label = {
                                            Text(
                                                when (lang.lowercase()) {
                                                    "en" -> "English"
                                                    "all" -> "All"
                                                    else -> lang.uppercase()
                                                },
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }

                    item {
                        val paidLabel = when (state.paidFilter) {
                            MergedMangaViewModel.PaidFilter.FREE -> "Free"
                            MergedMangaViewModel.PaidFilter.PAID -> "Paid"
                            MergedMangaViewModel.PaidFilter.ALL -> "All"
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.togglePaidPanel() }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Lock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = "  Access · $paidLabel" +
                                    if (state.paidChapterCount > 0) {
                                        " · ${state.paidChapterCount} paid raw"
                                    } else {
                                        ""
                                    },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                imageVector = if (state.paidPanelExpanded) {
                                    Icons.Outlined.KeyboardArrowUp
                                } else {
                                    Icons.Outlined.KeyboardArrowDown
                                },
                                contentDescription = null,
                            )
                        }

                        if (state.paidPanelExpanded) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                FilterChip(
                                    selected = state.paidFilter ==
                                        MergedMangaViewModel.PaidFilter.FREE,
                                    onClick = {
                                        viewModel.setPaidFilter(
                                            MergedMangaViewModel.PaidFilter.FREE,
                                        )
                                    },
                                    label = { Text("Free") },
                                )
                                FilterChip(
                                    selected = state.paidFilter ==
                                        MergedMangaViewModel.PaidFilter.PAID,
                                    onClick = {
                                        viewModel.setPaidFilter(
                                            MergedMangaViewModel.PaidFilter.PAID,
                                        )
                                    },
                                    label = { Text("Paid") },
                                )
                                FilterChip(
                                    selected = state.paidFilter ==
                                        MergedMangaViewModel.PaidFilter.ALL,
                                    onClick = {
                                        viewModel.setPaidFilter(
                                            MergedMangaViewModel.PaidFilter.ALL,
                                        )
                                    },
                                    label = { Text("All") },
                                )
                            }
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Linked Sources (${state.references.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                if (state.references.isEmpty()) {
                    item {
                        Text(
                            text = "No sources linked yet. Tap \"Re-link sources\".",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(state.references) { ref ->
                        SourceCard(
                            title = ref.mangaTitle ?: "Unknown",
                            sourceName = ref.sourceName ?: "Source ${ref.sourceId}",
                            chapterCount = ref.chapterCount,
                            priority = ref.priority,
                            onClick = {
                                navigator.push(
                                    GlobalSearchScreen(
                                        searchQuery = ref.mangaTitle ?: title,
                                    ),
                                )
                            },
                        )
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    val chapterTitle = if (state.allChapters.size != state.displayChapters.size) {
                        "Chapters (${state.displayChapters.size})  ·  ${state.allChapters.size} raw"
                    } else {
                        "Chapters (${state.displayChapters.size})"
                    }
                    Text(
                        text = chapterTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                if (state.displayChapters.isEmpty()) {
                    item {
                        Text(
                            text = if (state.allChapters.isEmpty()) {
                                "No chapters yet. Tap \"Fetch chapters\"."
                            } else {
                                "No chapters for this filter. Try Language All or Access All."
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(
                        items = state.displayChapters,
                        key = { ch -> ch.sourceId.toString() + "_" + ch.url },
                    ) { chapter ->
                        val sourceName = state.references
                            .find { it.sourceId == chapter.sourceId }
                            ?.sourceName
                            ?: "Source ${chapter.sourceId}"
                        ChapterCard(
                            chapter = chapter,
                            sourceName = sourceName,
                            onClick = { viewModel.openChapter(chapter) },
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun SourceCard(
        title: String,
        sourceName: String,
        chapterCount: Int,
        priority: Int,
        onClick: () -> Unit,
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = sourceName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (chapterCount > 0) {
                        "$chapterCount chapters • score $priority"
                    } else {
                        "Match score: $priority"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    @Composable
    private fun ChapterCard(
        chapter: MergedChapter,
        sourceName: String,
        onClick: () -> Unit,
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = chapter.name,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = buildString {
                        if (chapter.chapterNumber >= 0) {
                            append("Ch. ${chapter.chapterNumber}")
                        }
                        if (!chapter.language.isNullOrBlank()) {
                            if (isNotEmpty()) append(" • ")
                            append(chapter.language)
                        }
                        if (isNotEmpty()) append(" • ")
                        append(sourceName)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
