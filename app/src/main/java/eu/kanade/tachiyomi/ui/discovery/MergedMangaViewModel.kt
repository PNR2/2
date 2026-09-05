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
import eu.kanade.tachiyomi.data.discovery.MergedChapter
import eu.kanade.tachiyomi.data.discovery.MergedManga
import eu.kanade.tachiyomi.data.discovery.MergedMangaManager
import eu.kanade.tachiyomi.data.discovery.MergedMangaReference
import eu.kanade.tachiyomi.data.discovery.MergedMangaRepository
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager

@AssistedInject
class MergedMangaViewModel(
    @Assisted private val initialManga: MergedManga,
    private val sourceManager: SourceManager,
    private val networkToLocalManga: NetworkToLocalManga,
) : ViewModel() {

    private val repository = MergedMangaRepository()
    private val manager = MergedMangaManager()

    private val _state = MutableStateFlow(
        State(
            manga = initialManga,
            references = repository.getReferences(initialManga.id),
            chapters = repository.getChapters(initialManga.id),
        ),
    )
    val state: StateFlow<State> = _state.asStateFlow()

    private val _openManga = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val openManga: SharedFlow<Long> = _openManga.asSharedFlow()

    fun relink() {
        if (_state.value.isRelinking) return
        _state.update {
            it.copy(isRelinking = true, statusText = "Searching extensions...")
        }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    manager.createOrUpdateMergedManga(
                        title = initialManga.title,
                        coverUrl = initialManga.coverUrl,
                        synopsis = initialManga.synopsis,
                        author = initialManga.author,
                        malId = initialManga.malId,
                    )
                }
                val refs = repository.getReferences(initialManga.id)
                _state.update {
                    it.copy(
                        isRelinking = false,
                        references = refs,
                        statusText = "Done. Sources: ${refs.size}",
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isRelinking = false,
                        statusText = "Error: ${e.message}",
                    )
                }
            }
        }
    }

    fun fetchChapters() {
        val current = _state.value
        if (current.isFetchingChapters || current.references.isEmpty()) return

        _state.update {
            it.copy(
                isFetchingChapters = true,
                statusText = "Fetching chapters from ${it.references.size} sources...",
            )
        }

        viewModelScope.launch {
            try {
                val allChapters = withContext(Dispatchers.IO) {
                    fetchChaptersFromSources(
                        references = current.references,
                        mergedId = initialManga.id,
                    )
                }
                repository.addChapters(allChapters)
                current.references.forEach { ref ->
                    val count = allChapters.count { it.sourceId == ref.sourceId }
                    if (count > 0) {
                        repository.updateReferenceChapterCount(
                            mergedId = initialManga.id,
                            sourceId = ref.sourceId,
                            mangaUrl = ref.mangaUrl,
                            count = count,
                        )
                    }
                }
                val chapters = repository.getChapters(initialManga.id)
                val refs = repository.getReferences(initialManga.id)
                _state.update {
                    it.copy(
                        isFetchingChapters = false,
                        chapters = chapters,
                        references = refs,
                        statusText = "Fetched ${chapters.size} chapters",
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isFetchingChapters = false,
                        statusText = "Error: ${e.message}",
                    )
                }
            }
        }
    }

    /**
     * Opens the real manga page for the source that owns this chapter.
     * From there the user can read any chapter of that source.
     */
    fun openChapter(chapter: MergedChapter) {
        viewModelScope.launch {
            try {
                _state.update { it.copy(statusText = "Opening chapter...") }

                val ref = _state.value.references.find { it.sourceId == chapter.sourceId }
                    ?: run {
                        _state.update { it.copy(statusText = "Source not found for chapter") }
                        return@launch
                    }

                val source = sourceManager.get(chapter.sourceId)
                    ?: run {
                        _state.update { it.copy(statusText = "Source not installed") }
                        return@launch
                    }

                val sManga = SManga.create().apply {
                    url = ref.mangaUrl
                    title = ref.mangaTitle ?: initialManga.title
                    if (!initialManga.coverUrl.isNullOrEmpty()) {
                        thumbnail_url = initialManga.coverUrl
                    }
                    if (!initialManga.synopsis.isNullOrEmpty()) {
                        description = initialManga.synopsis
                    }
                    initialized = true
                }

                val localManga: Manga = withContext(Dispatchers.IO) {
                    networkToLocalManga(sManga.toDomainManga(source.id))
                }

                _openManga.emit(localManga.id)
                _state.update { it.copy(statusText = "") }
            } catch (e: Exception) {
                _state.update { it.copy(statusText = "Open error: ${e.message}") }
            }
        }
    }

    private suspend fun fetchChaptersFromSources(
        references: List<MergedMangaReference>,
        mergedId: Long,
    ): List<MergedChapter> = coroutineScope {
        val deferredList = references.map { ref ->
            async {
                try {
                    val source: Source = sourceManager.get(ref.sourceId)
                        ?: return@async emptyList<MergedChapter>()

                    val sManga = SManga.create().apply {
                        url = ref.mangaUrl
                        title = ref.mangaTitle ?: ""
                    }

                    val chapterList: List<SChapter> = withTimeoutOrNull(20000) {
                        val update = source.getMangaUpdate(
                            manga = sManga,
                            chapters = emptyList(),
                            fetchDetails = false,
                            fetchChapters = true,
                        )
                        update.chapters
                    } ?: return@async emptyList<MergedChapter>()

                    chapterList.map { ch: SChapter ->
                        MergedChapter(
                            mergedId = mergedId,
                            sourceId = ref.sourceId,
                            url = ch.url,
                            name = ch.name,
                            chapterNumber = ch.chapter_number,
                            language = source.lang,
                            dateUpload = ch.date_upload,
                        )
                    }
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }

        deferredList.awaitAll()
            .flatten()
            .distinctBy { it.sourceId.toString() + "_" + it.url }
            .sortedWith(
                compareBy<MergedChapter> { it.chapterNumber }
                    .thenBy { it.name },
            )
    }

    private fun SManga.toDomainManga(sourceId: Long): Manga {
        return Manga.create().copy(
            url = this.url,
            title = this.title,
            artist = this.artist,
            author = this.author,
            description = this.description,
            genre = this.genre,
            status = this.status.toLong(),
            thumbnailUrl = this.thumbnail_url,
            source = sourceId,
            initialized = this.initialized,
        )
    }

    data class State(
        val manga: MergedManga,
        val references: List<MergedMangaReference> = emptyList(),
        val chapters: List<MergedChapter> = emptyList(),
        val isRelinking: Boolean = false,
        val isFetchingChapters: Boolean = false,
        val statusText: String = "",
    )

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(initialManga: MergedManga): MergedMangaViewModel
    }
}
