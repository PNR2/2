@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.ui.discovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
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
    @Assisted private val unused: Unit = Unit,
    private val sourceManager: SourceManager,
) : ViewModel() {

    private val repository = MalDiscoveryRepository()
    private val manager = MergedMangaManager(sourceManager)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _openMerged = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val openMerged: SharedFlow<Long> = _openMerged.asSharedFlow()

    init {
        refreshList()
    }

    fun refreshList() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = try {
                repository.getSeasonalManga()
            } catch (_: Exception) {
                emptyList()
            }
            _state.update {
                it.copy(
                    items = list,
                    statusText = if (list.isEmpty()) {
                        "No seasonal manga yet. Pull refresh in Discovery."
                    } else {
                        ""
                    },
                )
            }
        }
    }

    /**
     * Vision: tap seasonal card → search all extensions in background → open cohesive entry.
     */
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
                        author = null,
                        malId = item.malId,
                    )
                }
                if (mergedId > 0) {
                    _openMerged.emit(mergedId)
                    _state.update {
                        it.copy(
                            openingMalId = null,
                            statusText = "",
                        )
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
        fun create(unused: Unit = Unit): SeasonalViewModel
    }
}
