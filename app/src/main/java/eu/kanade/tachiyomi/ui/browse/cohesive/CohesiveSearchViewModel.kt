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

@AssistedInject
class CohesiveSearchViewModel(
    @Assisted private val initialQuery: String = "",
) : ViewModel() {

    private val repository = MergedMangaRepository()
    private val manager = MergedMangaManager()

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
                it.copy(isSearching = false, result = null, statusText = "")
            }
            return
        }
        // Debounce — wait until user finishes typing
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
                    result = null,
                )
            }
            try {
                val mergedId = withContext(Dispatchers.IO) {
                    manager.createOrUpdateMergedManga(
                        title = q,
                    )
                }
                val manga = withContext(Dispatchers.IO) {
                    repository.getMergedMangaById(mergedId)
                }
                val refs = withContext(Dispatchers.IO) {
                    repository.getReferences(mergedId)
                }
                _state.update {
                    it.copy(
                        isSearching = false,
                        result = manga,
                        statusText = if (refs.isEmpty()) {
                            "No sources found"
                        } else {
                            "Linked ${refs.size} sources"
                        },
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
        val result: MergedManga? = null,
        val saved: List<MergedManga> = emptyList(),
    )

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(initialQuery: String = ""): CohesiveSearchViewModel
    }
}
