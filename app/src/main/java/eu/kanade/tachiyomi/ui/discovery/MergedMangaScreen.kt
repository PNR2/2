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
import eu.kanade.tachiyomi.data.discovery.MergedManga
import eu.kanade.tachiyomi.data.discovery.MergedMangaManager
import eu.kanade.tachiyomi.data.discovery.MergedMangaReference
import eu.kanade.tachiyomi.data.discovery.MergedMangaRepository
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

        var references by remember {
            mutableStateOf(repository.getReferences(mergedManga.id))
        }
        var isRelinking by remember { mutableStateOf(false) }
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
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    if (!mergedManga.coverUrl.isNullOrEmpty()) {
                        AsyncImage(
                            model = mergedManga.coverUrl,
                            contentDescription = "Cover",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(260.dp),
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
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = mergedManga.synopsis,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

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
                                    statusText = "Done. Sources found: ${references.size}"
                                } catch (e: Exception) {
                                    statusText = "Error: ${e.message}"
                                } finally {
                                    isRelinking = false
                                }
                            }
                        },
                        enabled = !isRelinking,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (isRelinking) "Linking..." else "Re-link now")
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
                            text = "No sources linked yet. Tap \"Re-link now\".",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(references) { ref ->
                        SourceCard(
                            title = ref.mangaTitle ?: "Unknown title",
                            sourceName = ref.sourceName ?: "Source ${ref.sourceId}",
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
            }
        }
    }

    @Composable
    private fun SourceCard(
        title: String,
        sourceName: String,
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
                    text = "Match score: $priority",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
