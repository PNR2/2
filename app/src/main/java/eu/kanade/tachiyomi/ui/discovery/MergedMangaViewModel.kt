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
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
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
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.interactor.ShouldUpdateDbChapter
import tachiyomi.domain.chapter.interactor.SyncChaptersWithSource
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager

@AssistedInject
class MergedMangaViewModel(
    @Assisted private val mergedId: Long,
    private val sourceManager: SourceManager,
    private val networkToLocalManga: NetworkToLocalManga,
    private val syncChaptersWithSource: SyncChaptersWithSource,
    private val getChapter: GetChapter,
) : ViewModel() {

    private val repository = MergedMangaRepository()
    private val manager = MergedMangaManager()

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** mangaId + chapterId → open ReaderActivity */
    private val _openReader = MutableSharedFlow<OpenReader>(extraBufferCapacity = 1)
    val openReader: SharedFlow<OpenReader> = _openReader.asSharedFlow()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch(Dispatchers.IO) {
            val manga = repository.getMergedMangaById(mergedId)
            val refs = repository.getReferences(mergedId)
            val chapters = repository.getChapters(mergedId)
            _state.update {
                it.copy(
                    manga = manga,
                    references = refs,
                    allChapters = chapters,
                    isLoading = false,
                ).withFilteredChapters()
            }
        }
    }

    fun setLanguageFilter(filter: String) {
        _state.update {
            it.copy(languageFilter = filter).withFilteredChapters()
        }
    }

    fun relink() {
        val manga = _state.value.manga ?: return
        if (_state.value.isRelinking) return
        _state.update {
            it.copy(isRelinking = true, statusText = "Searching extensions...")
        }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    manager.createOrUpdateMergedManga(
                        title = manga.title,
                        coverUrl = manga.coverUrl,
                        synopsis = manga.synopsis,
                        author = manga.author,
                        malId = manga.malId,
                    )
                }
                val refs = repository.getReferences(mergedId)
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
                        mergedId = mergedId,
                    )
                }
                repository.addChapters(allChapters)
                current.references.forEach { ref ->
                    val count = allChapters.count { it.sourceId == ref.sourceId }
                    if (count > 0) {
                        repository.updateReferenceChapterCount(
                            mergedId = mergedId,
                            sourceId = ref.sourceId,
                            mangaUrl = ref.mangaUrl,
                            count = count,
                        )
                    }
                }
                val chapters = repository.getChapters(mergedId)
                val refs = repository.getReferences(mergedId)
                _state.update {
                    val next = it.copy(
                        isFetchingChapters = false,
                        allChapters = chapters,
                        references = refs,
                    ).withFilteredChapters()
                    next.copy(
                        statusText = "Fetched ${chapters.size} chapters → " +
                            "${next.displayChapters.size} unique",
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
     * Unified read:
     * 1) Resolve source + local manga
     * 2) Sync that source's chapters into DB
     * 3) Find chapter by URL
     * 4) Open ReaderActivity on that chapter
     */
    fun openChapter(mergedChapter: MergedChapter) {
        viewModelScope.launch {
            try {
                _state.update { it.copy(statusText = "Opening reader...") }

                val ref = _state.value.references.find { it.sourceId == mergedChapter.sourceId }
                    ?: run {
                        _state.update { it.copy(statusText = "Source not found") }
                        return@launch
                    }

                val source = sourceManager.get(mergedChapter.sourceId)
                    ?: run {
                        _state.update { it.copy(statusText = "Source not installed") }
                        return@launch
                    }

                val title = ref.mangaTitle ?: _state.value.manga?.title ?: ""
                val sManga = SManga.create().apply {
                    url = ref.mangaUrl
                    this.title = title
                    val cover = _state.value.manga?.coverUrl
                    if (!cover.isNullOrEmpty()) thumbnail_url = cover
                    val syn = _state.value.manga?.synopsis
                    if (!syn.isNullOrEmpty()) description = syn
                    initialized = true
                }

                val result = withContext(Dispatchers.IO) {
                    val localManga = networkToLocalManga(sManga.toDomainManga(source.id))

                    // Pull chapter list from source and sync into DB
                    val remoteChapters: List<SChapter> = withTimeoutOrNull(20000) {
                        val update = source.getMangaUpdate(
                            manga = sManga,
                            chapters = emptyList(),
                            fetchDetails = false,
                            fetchChapters = true,
                        )
                        update.chapters
                    } ?: emptyList()

                    if (remoteChapters.isNotEmpty()) {
                        try {
                            syncChaptersWithSource.await(remoteChapters, localManga, source)
                        } catch (_: Exception) {
                            // still try to resolve chapter below
                        }
                    }

                    // Prefer exact URL match
                    var dbChapter = getChapter.await(mergedChapter.url, localManga.id)

                    // Fallback: match by chapter number
                    if (dbChapter == null) {
                        val targetNum = effectiveNumber(mergedChapter)
                        if (targetNum >= 0f) {
                            val all = remoteChapters // already synced; resolve via getChapter by each url
                            for (sc in all) {
                                val n = if (sc.chapter_number > 0f) {
                                    sc.chapter_number
                                } else {
                                    -1f
                                }
                                if (n == targetNum) {
                                    dbChapter = getChapter.await(sc.url, localManga.id)
                                    if (dbChapter != null) break
                                }
                            }
                        }
                    }

                    // Last fallback: create a temporary match using the merged URL after sync
                    if (dbChapter == null && remoteChapters.isNotEmpty()) {
                        val byName = remoteChapters.find {
                            it.url == mergedChapter.url ||
                                it.name.equals(mergedChapter.name, ignoreCase = true)
                        }
                        if (byName != null) {
                            dbChapter = getChapter.await(byName.url, localManga.id)
                        }
                    }

                    if (dbChapter == null) {
                        null
                    } else {
                        OpenReader(mangaId = localManga.id, chapterId = dbChapter.id)
                    }
                }

                if (result == null) {
                    _state.update {
                        it.copy(statusText = "Chapter not found on source. Try Fetch chapters again.")
                    }
                    return@launch
                }

                _openReader.emit(result)
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
    }

    private fun SManga.toDomainManga(sourceId: Long): Manga {
        val genreList: List<String>? = this.genre
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.ifEmpty { null }

        return Manga.create().copy(
            url = this.url,
            title = this.title,
            artist = this.artist,
            author = this.author,
            description = this.description,
            genre = genreList,
            status = this.status.toLong(),
            thumbnailUrl = this.thumbnail_url,
            source = sourceId,
            initialized = this.initialized,
        )
    }

    private fun effectiveNumber(ch: MergedChapter): Float {
        if (ch.chapterNumber > 0f) return ch.chapterNumber
        val patterns = listOf(
            Regex("""(?i)(?:ch(?:apter)?\.?\s*)(\d+(?:\.\d+)?)"""),
            Regex("""(?i)(?:c\.?\s*)(\d+(?:\.\d+)?)"""),
            Regex("""(?i)^(\d+(?:\.\d+)?)(?:\s|$)"""),
        )
        for (p in patterns) {
            val m = p.find(ch.name)
            if (m != null) {
                return m.groupValues[1].toFloatOrNull() ?: continue
            }
        }
        return -1f
    }

    private fun State.withFilteredChapters(): State {
        val refPriority = references.associate { it.sourceId to it.priority }
        val refChapterCount = references.associate { it.sourceId to it.chapterCount }

        val languageFiltered = when {
            languageFilter.equals("all", ignoreCase = true) -> allChapters
            else -> allChapters.filter { ch ->
                val lang = ch.language?.lowercase()?.trim().orEmpty()
                lang == languageFilter.lowercase() ||
                    (
                        languageFilter.equals("en", ignoreCase = true) &&
                            (lang.isEmpty() || lang == "en" || lang == "gb")
                        )
            }
        }

        val grouped = languageFiltered.groupBy { ch ->
            val n = effectiveNumber(ch)
            if (n >= 0f) "n:\( n" else "t: \){ch.name.trim().lowercase()}"
        }

        val unique = grouped.values.map { group ->
            group.sortedWith(
                compareByDescending<MergedChapter> { ch ->
                    val lang = ch.language?.lowercase().orEmpty()
                    when {
                        lang == "en" || lang == "gb" || lang.isEmpty() -> 3
                        else -> 0
                    }
                }.thenByDescending { ch ->
                    refChapterCount[ch.sourceId] ?: 0
                }.thenByDescending { ch ->
                    refPriority[ch.sourceId] ?: 0
                }.thenByDescending { ch ->
                    ch.dateUpload
                },
            ).first()
        }.sortedWith(
            compareBy<MergedChapter> { effectiveNumber(it) }
                .thenBy { it.name.lowercase() },
        )

        val languages = allChapters
            .mapNotNull { it.language?.lowercase()?.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()

        return copy(
            displayChapters = unique,
            availableLanguages = languages,
        )
    }

    data class OpenReader(
        val mangaId: Long,
        val chapterId: Long,
    )

    data class State(
        val manga: MergedManga? = null,
        val references: List<MergedMangaReference> = emptyList(),
        val allChapters: List<MergedChapter> = emptyList(),
        val displayChapters: List<MergedChapter> = emptyList(),
        val availableLanguages: List<String> = emptyList(),
        val languageFilter: String = "en",
        val isLoading: Boolean = true,
        val isRelinking: Boolean = false,
        val isFetchingChapters: Boolean = false,
        val statusText: String = "",
    )

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(mergedId: Long): MergedMangaViewModel
    }
}
