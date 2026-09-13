@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.ui.discovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import eu.kanade.tachiyomi.data.discovery.MalDiscoveryItem
import eu.kanade.tachiyomi.data.discovery.MalDiscoveryRepository
import eu.kanade.tachiyomi.data.discovery.MergedMangaManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.source.service.SourceManager

@AssistedInject
class SeasonalViewModel(
    private val sourceManager: SourceManager,
) : ViewModel() {

    private val repository = MalDiscoveryRepository()
    private val manager = MergedMangaManager(sourceManager)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _openMerged = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val openMerged: SharedFlow<Long> = _openMerged.asSharedFlow()

    init {
        viewModelScope.launch {
            repository.subscribeToSeasonalManga().collect { list ->
                _state.update {
                    it.copy(
                        items = list,
                        statusText = if (list.isEmpty() && it.openingMalId == null) {
                            "No seasonal manga yet. Refresh Discovery."
                        } else if (it.openingMalId == null) {
                            ""
                        } else {
                            it.statusText
                        },
                    )
                }
            }
        }
    }

    fun openCohesive(item: MalDiscoveryItem) {
        if (_state.value.openingMalId != null) return

        _state.update {
            it.copy(
                openingMalId = item.malId,
                statusText = "Building cohesive entry for ${item.title}…",
            )
        }

        viewModelScope.launch {
            try {
                val mergedId = withContext(Dispatchers.IO) {
                    manager.createOrUpdateMergedManga(
                        title = item.title,
                        coverUrl = item.coverUrl,
                        synopsis = item.synopsis,
                        author = item.authors,
                        malId = item.malId,
                    )
                }
                if (mergedId > 0) {
                    _openMerged.emit(mergedId)
                    _state.update {
                        it.copy(openingMalId = null, statusText = "")
                    }
                } else {
                    _state.update {
                        it.copy(
                            openingMalId = null,
                            statusText = "Could not create entry",
                        )
                    }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        openingMalId = null,
                        statusText = "Error: ${e.message}",
                    )
                }
            }
        }
    }

    data class State(
        val items: List<MalDiscoveryItem> = emptyList(),
        val openingMalId: Long? = null,
        val statusText: String = "",
    )

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(): SeasonalViewModel
    }
}
