@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.ui.discovery

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
        val coverUrl = manga?.coverUrl
        val synopsis = manga?.synopsis
        val title = manga?.title ?: "..."

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
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
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
                    if (!coverUrl.isNullOrEmpty()) {
                        AsyncImage(
                            model = coverUrl,
                            contentDescription = "Cover",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(240.dp),
                            contentScale = ContentScale.Crop,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )

                    if (!synopsis.isNullOrEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = synopsis,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = { viewModel.relink() },
                        enabled = !state.isRelinking && !state.isFetchingChapters && manga != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (state.isRelinking) "Linking..." else "Re-link sources")
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
                                "Fetching chapters..."
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

                if (state.allChapters.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Language",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
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
                                addAll(state.availableLanguages.filter { it != "en" })
                            }.distinct()

                            filters.forEach { lang ->
                                FilterChip(
                                    selected = state.languageFilter.equals(lang, ignoreCase = true),
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
                                "No chapters for this language. Try \"All\"."
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(state.displayChapters) { chapter ->
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
