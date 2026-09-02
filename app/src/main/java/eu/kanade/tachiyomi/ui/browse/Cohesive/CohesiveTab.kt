package eu.kanade.tachiyomi.ui.browse.cohesive

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import eu.kanade.presentation.components.TabContent
import eu.kanade.tachiyomi.data.discovery.MergedManga
import eu.kanade.tachiyomi.data.discovery.MergedMangaManager
import eu.kanade.tachiyomi.data.discovery.MergedMangaRepository
import eu.kanade.tachiyomi.ui.discovery.MergedMangaScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.i18n.MR

fun cohesiveTab(): TabContent {
    return TabContent(
        titleRes = MR.strings.browse,
        searchEnabled = true,
        content = { contentPadding, searchQuery ->
            CohesiveSearchContent(
                contentPadding = contentPadding,
                searchQuery = searchQuery,
            )
        },
    )
}

@Composable
private fun CohesiveSearchContent(
    contentPadding: PaddingValues,
    searchQuery: String?,
) {
    val navigator = LocalNavigator.currentOrThrow
    val scope = rememberCoroutineScope()
    val manager = remember { MergedMangaManager() }
    val repository = remember { MergedMangaRepository() }

    var isSearching by remember { mutableStateOf(false) }
    var progressText by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<MergedManga?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }

    // Start search when query is not empty
    androidx.compose.runtime.LaunchedEffect(searchQuery) {
        if (searchQuery.isNullOrBlank()) {
            result = null
            errorText = null
            progressText = ""
            return@LaunchedEffect
        }

        isSearching = true
        progressText = "Searching all extensions..."
        errorText = null
        result = null

        try {
            val mergedId = withContext(Dispatchers.IO) {
                manager.createOrUpdateMergedManga(
                    title = searchQuery.trim(),
                )
            }

            // Get the entry we just created/updated
            val all = repository.subscribeToMergedManga().value
            val found = all.find { it.id == mergedId }

            result = found
            progressText = if (found != null) {
                "Done"
            } else {
                "Entry created"
            }
        } catch (e: Exception) {
            errorText = e.message ?: "Search failed"
            progressText = ""
        } finally {
            isSearching = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        if (isSearching) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Text(
                text = progressText,
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        when {
            searchQuery.isNullOrBlank() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Search for a manga to create a Cohesive entry",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            errorText != null -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = errorText ?: "Error",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            result != null -> {
                val manga = result!!
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    navigator.push(MergedMangaScreen(manga))
                                },
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                        ) {
                            Column {
                                if (!manga.coverUrl.isNullOrEmpty()) {
                                    AsyncImage(
                                        model = manga.coverUrl,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(220.dp),
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
                                    Text(
                                        text = "Cohesive Entry",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            else -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isSearching) {
                        CircularProgressIndicator()
                    } else {
                        Text(
                            text = "No result",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
