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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.presentation.components.TabContent
import eu.kanade.tachiyomi.data.discovery.MalDiscoveryItem
import kotlinx.coroutines.flow.collectLatest
import tachiyomi.i18n.MR

fun seasonalTab(): TabContent {
    return TabContent(
        titleRes = MR.strings.browse,
        searchEnabled = false,
    ) { contentPadding, _ ->
        SeasonalTabContent(contentPadding = contentPadding)
    }
}

@Composable
private fun SeasonalTabContent(
    contentPadding: PaddingValues,
) {
    val navigator = LocalNavigator.currentOrThrow

    val viewModel = assistedMetroViewModel<SeasonalViewModel, SeasonalViewModel.Factory> {
        create()
    }
    val state by viewModel.state.collectAsState()

    LaunchedEffect(viewModel) {
        viewModel.openMerged.collectLatest { mergedId ->
            navigator.push(MergedMangaScreen(mergedId = mergedId))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        if (state.statusText.isNotEmpty()) {
            Text(
                text = state.statusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        if (state.items.isEmpty()) {
            Text(
                text = "No seasonal manga cached yet.\nUse Discovery refresh / sync first.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(140.dp),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(
                    items = state.items,
                    key = { it.malId },
                ) { item ->
                    SeasonalCard(
                        item = item,
                        isOpening = state.openingMalId == item.malId,
                        onClick = { viewModel.openCohesive(item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SeasonalCard(
    item: MalDiscoveryItem,
    isOpening: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isOpening, onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column {
            if (!item.coverUrl.isNullOrEmpty()) {
                AsyncImage(
                    model = item.coverUrl,
                    contentDescription = item.title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    contentScale = ContentScale.Crop,
                )
            }
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.score != null && item.score > 0.0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "⭐ ${item.score}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (isOpening) {
                    Spacer(modifier = Modifier.height(8.dp))
                    CircularProgressIndicator(
                        modifier = Modifier
                            .height(20.dp)
                            .align(Alignment.CenterHorizontally),
                        strokeWidth = 2.dp,
                    )
                }
            }
        }
    }
}
