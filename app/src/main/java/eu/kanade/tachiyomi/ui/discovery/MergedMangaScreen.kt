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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import eu.kanade.tachiyomi.data.discovery.MergedChapter
import eu.kanade.tachiyomi.data.discovery.MergedManga
import eu.kanade.tachiyomi.data.discovery.MergedMangaManager
import eu.kanade.tachiyomi.data.discovery.MergedMangaReference
import eu.kanade.tachiyomi.data.discovery.MergedMangaRepository
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

data class MergedMangaScreen(
    private val mergedManga: MergedManga,
) : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val repository = remember { MergedMangaRepository() }
        val manager = remember { MergedMangaManager() }
        val scope = rememberCoroutineScope()

        val sourceManager = remember {
            try {
                Injekt.get<SourceManager>()
            } catch (_: Exception) {
                null
            }
        }

        var references by remember {
            mutableStateOf(repository.getReferences(mergedManga.id))
        }
        var chapters by remember {
            mutableStateOf(repository.getChapters(mergedManga.id))
        }
        var isRelinking by remember { mutableStateOf(false) }
        var isFetchingChapters by remember { mutableStateOf(false) }
        var statusText by remember { mutableStateOf("") }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(mergedManga.title, maxLines = 1) },
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
                    if (!mergedManga.coverUrl.isNullOrEmpty()) {
                        AsyncImage(
                            model = mergedManga.coverUrl,
                            contentDescription = "Cover",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(240.dp),
                            contentScale = ContentScale.Crop,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    Text(
                        text = mergedManga.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )

                    if (!mergedManga.synopsis.isNullOrEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = mergedManga.synopsis,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            if (isRelinking) return@Button
                            isRelinking = true
                            statusText = "Searching extensions..."
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        manager.createOrUpdateMergedManga(
                                            title = mergedManga.title,
                                            coverUrl = mergedManga.coverUrl,
                                            synopsis = mergedManga.synopsis,
                                            author = mergedManga.author,
                                            malId = mergedManga.malId,
                                        )
                                    }
                                    references = repository.getReferences(mergedManga.id)
                                    statusText = "Done. Sources: ${references.size}"
                                } catch (e: Exception) {
                                    statusText = "Error: ${e.message}"
                                } finally {
                                    isRelinking = false
                                }
                            }
                        },
                        enabled = !isRelinking && !isFetchingChapters,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (isRelinking) "Linking..." else "Re-link sources")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = {
                            if (isFetchingChapters || references.isEmpty() || sourceManager == null) {
                                if (sourceManager == null) {
                                    statusText = "SourceManager not available"
                                }
                                return@Button
                            }
                            isFetchingChapters = true
                            statusText = "Fetching chapters from ${references.size} sources..."
                            scope.launch {
                                try {
                                    val allChapters = withContext(Dispatchers.IO) {
                                        fetchChaptersFromSources(
                                            sourceManager = sourceManager,
                                            references = references,
                                            mergedId = mergedManga.id,
                                        )
                                    }
                                    repository.addChapters(allChapters)
                                    references.forEach { ref ->
                                        val count = allChapters.count { it.sourceId == ref.sourceId }
                                        if (count > 0) {
                                            repository.updateReferenceChapterCount(
                                                mergedId = mergedManga.id,
                                                sourceId = ref.sourceId,
                                                mangaUrl = ref.mangaUrl,
                                                count = count,
                                            )
                                        }
                                    }
                                    chapters = repository.getChapters(mergedManga.id)
                                    references = repository.getReferences(mergedManga.id)
                                    statusText = "Fetched ${chapters.size} chapters"
                                } catch (e: Exception) {
                                    statusText = "Error: ${e.message}"
                                } finally {
                                    isFetchingChapters = false
                                }
                            }
                        },
                        enabled = !isFetchingChapters && !isRelinking && references.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (isFetchingChapters) "Fetching chapters..." else "Fetch chapters")
                    }

                    if (statusText.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Linked Sources (${references.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                if (references.isEmpty()) {
                    item {
                        Text(
                            text = "No sources linked yet.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(references) { ref ->
                        SourceCard(
                            title = ref.mangaTitle ?: "Unknown",
                            sourceName = ref.sourceName ?: "Source ${ref.sourceId}",
                            chapterCount = ref.chapterCount,
                            priority = ref.priority,
                            onClick = {
                                navigator.push(
                                    GlobalSearchScreen(
                                        searchQuery = ref.mangaTitle ?: mergedManga.title,
                                    ),
                                )
                            },
                        )
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Chapters (${chapters.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                if (chapters.isEmpty()) {
                    item {
                        Text(
                            text = "No chapters yet. Tap \"Fetch chapters\".",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(chapters.take(100)) { chapter ->
                        ChapterCard(chapter = chapter)
                    }
                    if (chapters.size > 100) {
                        item {
                            Text(
                                text = "... and ${chapters.size - 100} more",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    private suspend fun fetchChaptersFromSources(
        sourceManager: SourceManager,
        references: List<MergedMangaReference>,
        mergedId: Long,
    ): List<MergedChapter> = coroutineScope {
        val deferredList = references.map { ref ->
            async {
                try {
                    val source: Source = sourceManager.get(ref.sourceId)
                        ?: return@async emptyList<MergedChapter>()

                    val sManga = SManga.create().apply {
                        url = ref.mangaUrl
                        title = ref.mangaTitle ?: ""
                    }

                    val chapterList: List<SChapter> = withTimeoutOrNull(20000) {
                        val update = source.getMangaUpdate(
                            manga = sManga,
                            chapters = emptyList(),
                            fetchDetails = false,
                            fetchChapters = true,
                        )
                        update.chapters
                    } ?: return@async emptyList<MergedChapter>()

                    chapterList.map { ch: SChapter ->
                        MergedChapter(
                            mergedId = mergedId,
                            sourceId = ref.sourceId,
                            url = ch.url,
                            name = ch.name,
                            chapterNumber = ch.chapter_number,
                            language = source.lang,
                            dateUpload = ch.date_upload,
                        )
                    }
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }

        deferredList.awaitAll()
            .flatten()
            .distinctBy { chapter: MergedChapter ->
                chapter.sourceId.toString() + "_" + chapter.url
            }
            .sortedBy { chapter: MergedChapter ->
                chapter.chapterNumber
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
