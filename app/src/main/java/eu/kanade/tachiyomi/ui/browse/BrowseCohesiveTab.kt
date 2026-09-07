@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.ui.browse

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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.presentation.components.TabContent
import eu.kanade.tachiyomi.data.discovery.MergedManga
import eu.kanade.tachiyomi.ui.browse.cohesive.CohesiveSearchViewModel
import eu.kanade.tachiyomi.ui.discovery.MergedMangaScreen
import tachiyomi.i18n.MR

@Composable
fun cohesiveTab(): TabContent {
    return TabContent(
        titleRes = MR.strings.action_search,
        content = { contentPadding, _ ->
            CohesiveSearchContent(contentPadding = contentPadding)
        },
    )
}

@Composable
private fun CohesiveSearchContent(
    contentPadding: PaddingValues,
) {
    val navigator = LocalNavigator.currentOrThrow
    val focusManager = LocalFocusManager.current

    val viewModel = assistedMetroViewModel<
        CohesiveSearchViewModel,
        CohesiveSearchViewModel.Factory,
        > {
        create(initialQuery = "")
    }
    val state by viewModel.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 16.dp),
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = state.query,
            onValueChange = { viewModel.onQueryChange(it) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search cohesive manga…") },
            leadingIcon = {
                Icon(Icons.Outlined.Search, contentDescription = null)
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    focusManager.clearFocus()
                    viewModel.search()
                },
            ),
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (state.isSearching) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (state.statusText.isNotEmpty()) {
            Text(
                text = state.statusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(140.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            // Primary result
            state.primary?.let { manga ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = "Best match",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                item(key = "primary_${manga.id}") {
                    MangaCard(
                        manga = manga,
                        subtitle = "Cohesive entry",
                        onClick = {
                            navigator.push(MergedMangaScreen(mergedId = manga.id))
                        },
                    )
                }
            }

            // Similar titles
            if (state.similar.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Similar Manga Titles",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Separate series · tap to open, then Re-link / Fetch chapters if needed",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(state.similar, key = { "similar_${it.id}" }) { manga ->
                    MangaCard(
                        manga = manga,
                        subtitle = "Similar",
                        onClick = {
                            navigator.push(MergedMangaScreen(mergedId = manga.id))
                        },
                    )
                }
            }

            // Saved list
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Saved cohesive entries",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            if (state.saved.isEmpty() && !state.isSearching && state.primary == null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (state.query.isBlank()) {
                                "Search a title to build a cohesive entry"
                            } else {
                                "No results yet"
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(state.saved, key = { "saved_${it.id}" }) { manga ->
                    MangaCard(
                        manga = manga,
                        subtitle = null,
                        onClick = {
                            navigator.push(MergedMangaScreen(mergedId = manga.id))
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MangaCard(
    manga: MergedManga,
    subtitle: String?,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column {
            if (!manga.coverUrl.isNullOrEmpty()) {
                AsyncImage(
                    model = manga.coverUrl,
                    contentDescription = manga.title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    contentScale = ContentScale.Crop,
                )
            }
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = manga.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}
