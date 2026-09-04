@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.ui.browse.cohesive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import eu.kanade.tachiyomi.data.discovery.MergedManga
import eu.kanade.tachiyomi.data.discovery.MergedMangaReference
import eu.kanade.tachiyomi.data.discovery.MergedMangaRepository
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import tachiyomi.domain.source.service.SourceManager

@AssistedInject
class CohesiveSearchViewModel(
    @Assisted private val initialQuery: String,
    private val sourceManager: SourceManager,
) : ViewModel() {

    private val repository = MergedMangaRepository()

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var searchJob: Job? = null

    init {
        if (initialQuery.isNotBlank()) {
            search(initialQuery)
        }
    }

    fun search(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return

        searchJob?.cancel()
        _state.update {
            it.copy(
                isSearching = true,
                progressText = "Creating cohesive entry...",
                error = null,
                result = null,
                linkedCount = 0,
            )
        }

        searchJob = viewModelScope.launch {
            try {
                // 1. Create / update the main entry
                val mergedId = withContext(Dispatchers.IO) {
                    repository.createOrUpdateMergedManga(
                        MergedManga(
                            title = trimmed,
                            preferredLanguage = "en",
                        ),
                    )
                }

                _state.update { it.copy(progressText = "Searching extensions...") }

                // 2. Get sources
                val sources = sourceManager.getAll()
                    .filterIsInstance<CatalogueSource>()
                    .filter { it.lang.equals("en", ignoreCase = true) || it.lang == "all" }
                    .take(40)

                if (sources.isEmpty()) {
                    val entry = repository.subscribeToMergedManga().value.find { it.id == mergedId }
                    _state.update {
                        it.copy(
                            isSearching = false,
                            result = entry,
                            progressText = "No sources available",
                            linkedCount = 0,
                        )
                    }
                    return@launch
                }

                // 3. Search in parallel
                val matches = withContext(Dispatchers.IO) {
                    sources.map { source ->
                        async {
                            searchOneSource(source, trimmed)
                        }
                    }.awaitAll().flatten()
                }

                val accepted = matches
                    .distinctBy { it.sourceId.toString() + "_" + it.url }
                    .sortedByDescending { it.score }
                    .take(20)

                // 4. Save references (now with source name)
                accepted.forEach { match ->
                    repository.addReference(
                        MergedMangaReference(
                            mergedId = mergedId,
                            sourceId = match.sourceId,
                            sourceName = match.sourceName,
                            mangaUrl = match.url,
                            mangaTitle = match.title,
                            chapterCount = 0,
                            isInfoSource = match.score >= 80,
                            priority = match.score,
                        ),
                    )
                }

                val entry = repository.subscribeToMergedManga().value.find { it.id == mergedId }

                _state.update {
                    it.copy(
                        isSearching = false,
                        result = entry,
                        linkedCount = accepted.size,
                        progressText = if (accepted.isEmpty()) {
                            "No matching sources found"
                        } else {
                            "Linked ${accepted.size} sources"
                        },
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isSearching = false,
                        error = e.message ?: "Search failed",
                        progressText = "",
                    )
                }
            }
        }
    }

    private suspend fun searchOneSource(
        source: CatalogueSource,
        query: String,
    ): List<SearchMatch> {
        return try {
            withTimeoutOrNull(7000) {
                val page = source.getSearchManga(1, query, FilterList())
                page.mangas.take(6).mapNotNull { manga ->
                    val score = calculateScore(manga, query)
                    if (score >= 30) {
                        SearchMatch(
                            sourceId = source.id,
                            sourceName = source.name,
                            url = manga.url,
                            title = manga.title,
                            score = score,
                        )
                    } else {
                        null
                    }
                }
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun calculateScore(manga: SManga, query: String): Int {
        val mangaTitle = manga.title.trim().lowercase()
        val q = query.trim().lowercase()

        var score = 0
        when {
            mangaTitle == q -> score += 100
            mangaTitle.startsWith(q) || q.startsWith(mangaTitle) -> score += 80
            mangaTitle.contains(q) || q.contains(mangaTitle) -> score += 55
            else -> {
                val qWords = q.split(" ").filter { it.length > 2 }.toSet()
                val mWords = mangaTitle.split(" ").filter { it.length > 2 }.toSet()
                score += qWords.intersect(mWords).size * 12
            }
        }
        if (!manga.description.isNullOrBlank()) score += 5
        if (!manga.author.isNullOrBlank()) score += 3
        if (mangaTitle.length < 3) score -= 25
        return score.coerceIn(0, 120)
    }

    data class State(
        val isSearching: Boolean = false,
        val progressText: String = "",
        val result: MergedManga? = null,
        val linkedCount: Int = 0,
        val error: String? = null,
    )

    private data class SearchMatch(
        val sourceId: Long,
        val sourceName: String,
        val url: String,
        val title: String,
        val score: Int,
    )

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(initialQuery: String): CohesiveSearchViewModel
    }
}
