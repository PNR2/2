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
import eu.kanade.tachiyomi.data.discovery.MergedMangaManager
import eu.kanade.tachiyomi.data.discovery.MergedMangaRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.source.service.SourceManager

@AssistedInject
class CohesiveSearchViewModel(
    @Assisted private val initialQuery: String = "",
    private val sourceManager: SourceManager,
) : ViewModel() {

    private val repository = MergedMangaRepository()
    private val manager = MergedMangaManager(sourceManager)

    private val _state = MutableStateFlow(State(query = initialQuery))
    val state: StateFlow<State> = _state.asStateFlow()

    private var searchJob: Job? = null

    init {
        observeSaved()
        if (initialQuery.isNotBlank()) {
            search(initialQuery)
        }
    }

    private fun observeSaved() {
        viewModelScope.launch {
            repository.subscribeToMergedManga().collect { list ->
                _state.update { it.copy(saved = list) }
            }
        }
    }

    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query) }
        searchJob?.cancel()
        if (query.isBlank()) {
            _state.update {
                it.copy(
                    isSearching = false,
                    primary = null,
                    similar = emptyList(),
                    statusText = "",
                )
            }
            return
        }
        searchJob = viewModelScope.launch {
            delay(800)
            search(query)
        }
    }

    fun search(query: String = _state.value.query) {
        val q = query.trim()
        if (q.isEmpty()) return

        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    isSearching = true,
                    statusText = "Searching extensions…",
                    primary = null,
                    similar = emptyList(),
                )
            }
            try {
                // Progressive flow — primary appears as soon as sources match
                manager.searchCohesiveFlow(query = q).collect { event ->
                    when (event) {
                        is MergedMangaManager.SearchEvent.Progress -> {
                            _state.update {
                                it.copy(
                                    statusText = event.message +
                                        " (${event.done}/${event.total})",
                                )
                            }
                        }
                        is MergedMangaManager.SearchEvent.PrimaryReady -> {
                            val manga = withContext(Dispatchers.IO) {
                                repository.getMergedMangaById(event.id)
                            } ?: MergedManga(
                                id = event.id,
                                title = event.title,
                                coverUrl = event.coverUrl,
                            )
                            _state.update {
                                it.copy(
                                    primary = manga,
                                    statusText = "Best match · ${event.sourceCount} sources" +
                                        " (still searching…)",
                                )
                            }
                        }
                        is MergedMangaManager.SearchEvent.SourcesUpdated -> {
                            val manga = withContext(Dispatchers.IO) {
                                repository.getMergedMangaById(event.id)
                            }
                            _state.update {
                                it.copy(
                                    primary = manga ?: it.primary,
                                    statusText = "Linked ${event.sourceCount} sources" +
                                        " (still searching…)",
                                )
                            }
                        }
                        is MergedMangaManager.SearchEvent.SimilarReady -> {
                            val similarManga = withContext(Dispatchers.IO) {
                                event.items.mapNotNull { item ->
                                    repository.getMergedMangaById(item.id)
                                        ?: MergedManga(
                                            id = item.id,
                                            title = item.title,
                                            coverUrl = item.coverUrl,
                                        )
                                }
                            }
                            _state.update {
                                it.copy(similar = similarManga)
                            }
                        }
                        is MergedMangaManager.SearchEvent.Finished -> {
                            val primary = withContext(Dispatchers.IO) {
                                if (event.outcome.primaryId > 0) {
                                    repository.getMergedMangaById(event.outcome.primaryId)
                                } else {
                                    null
                                }
                            }
                            val similarManga = withContext(Dispatchers.IO) {
                                event.outcome.similar.mapNotNull { item ->
                                    repository.getMergedMangaById(item.id)
                                        ?: MergedManga(
                                            id = item.id,
                                            title = item.title,
                                            coverUrl = item.coverUrl,
                                        )
                                }
                            }
                            val refCount = if (event.outcome.primaryId > 0) {
                                withContext(Dispatchers.IO) {
                                    repository.getReferences(event.outcome.primaryId).size
                                }
                            } else {
                                0
                            }
                            _state.update {
                                it.copy(
                                    isSearching = false,
                                    primary = primary ?: it.primary,
                                    similar = similarManga,
                                    statusText = when {
                                        primary == null && it.primary == null -> "No result"
                                        refCount == 0 -> "Entry created (no sources)"
                                        similarManga.isNotEmpty() ->
                                            "Done · $refCount sources · ${similarManga.size} similar"
                                        else -> "Done · $refCount sources"
                                    },
                                )
                            }
                        }
                        is MergedMangaManager.SearchEvent.Failed -> {
                            _state.update {
                                it.copy(
                                    isSearching = false,
                                    statusText = "Error: ${event.message}",
                                )
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isSearching = false,
                        statusText = "Error: ${e.message}",
                    )
                }
            }
        }
    }

    data class State(
        val query: String = "",
        val isSearching: Boolean = false,
        val statusText: String = "",
        val primary: MergedManga? = null,
        val similar: List<MergedManga> = emptyList(),
        val saved: List<MergedManga> = emptyList(),
    )

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(initialQuery: String = ""): CohesiveSearchViewModel
    }
}
