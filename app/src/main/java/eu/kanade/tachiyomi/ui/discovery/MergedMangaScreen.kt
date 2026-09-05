@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.ui.discovery

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.tachiyomi.data.discovery.MergedChapter
import eu.kanade.tachiyomi.data.discovery.MergedManga
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen

data class MergedMangaScreen(
    private val mergedManga: MergedManga,
) : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow

        val viewModel = assistedMetroViewModel<MergedMangaViewModel, MergedMangaViewModel.Factory> {
            create(initialManga = mergedManga)
        }
        val state by viewModel.state.collectAsState()

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(state.manga.title, maxLines = 1) },
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
                    if (!state.manga.coverUrl.isNullOrEmpty()) {
                        AsyncImage(
                            model = state.manga.coverUrl,
                            contentDescription = "Cover",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(240.dp),
                            contentScale = ContentScale.Crop,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    Text(
                        text = state.manga.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )

                    if (!state.manga.synopsis.isNullOrEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = state.manga.synopsis,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = { viewModel.relink() },
                        enabled = !state.isRelinking && !state.isFetchingChapters,
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
                        Text(if (state.isFetchingChapters) "Fetching chapters..." else "Fetch chapters")
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
                                        searchQuery = ref.mangaTitle ?: state.manga.title,
                                    ),
                                )
                            },
                        )
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Chapters (${state.chapters.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                if (state.chapters.isEmpty()) {
                    item {
                        Text(
                            text = "No chapters yet. Tap \"Fetch chapters\".",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(state.chapters.take(100)) { chapter ->
                        ChapterCard(chapter = chapter)
                    }
                    if (state.chapters.size > 100) {
                        item {
                            Text(
                                text = "... and ${state.chapters.size - 100} more",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
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
    private fun ChapterCard(chapter: MergedChapter) {
        Card(
            modifier = Modifier.fillMaxWidth(),
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
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
