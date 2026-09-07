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
            delay(650)
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
                    statusText = "Searching all extensions…",
                    primary = null,
                    similar = emptyList(),
                )
            }
            try {
                val outcome = withContext(Dispatchers.IO) {
                    manager.searchCohesive(query = q)
                }

                val primary = if (outcome.primaryId > 0) {
                    withContext(Dispatchers.IO) {
                        repository.getMergedMangaById(outcome.primaryId)
                    }
                } else {
                    null
                }

                val similarManga = withContext(Dispatchers.IO) {
                    outcome.similar.mapNotNull { item ->
                        repository.getMergedMangaById(item.id)
                            ?: MergedManga(
                                id = item.id,
                                title = item.title,
                                coverUrl = item.coverUrl,
                            )
                    }
                }

                val refCount = if (outcome.primaryId > 0) {
                    withContext(Dispatchers.IO) {
                        repository.getReferences(outcome.primaryId).size
                    }
                } else {
                    0
                }

                val status = when {
                    primary == null -> "No result"
                    refCount == 0 && similarManga.isEmpty() -> "Entry created (no sources)"
                    similarManga.isNotEmpty() -> "Linked $refCount sources · ${similarManga.size} similar"
                    else -> "Linked $refCount sources"
                }

                _state.update {
                    it.copy(
                        isSearching = false,
                        primary = primary,
                        similar = similarManga,
                        statusText = status,
                    )
                }
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
