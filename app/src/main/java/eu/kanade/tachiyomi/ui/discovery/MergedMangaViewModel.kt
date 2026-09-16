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
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.tachiyomi.data.discovery.MergedChapter
import eu.kanade.tachiyomi.data.discovery.MergedManga
import eu.kanade.tachiyomi.data.discovery.MergedMangaManager
import eu.kanade.tachiyomi.data.discovery.MergedMangaReference
import eu.kanade.tachiyomi.data.discovery.MergedMangaRepository
import eu.kanade.tachiyomi.data.discovery.ScanlationFetcher
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
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import java.util.Locale
import kotlin.math.abs

@AssistedInject
class MergedMangaViewModel(
    @Assisted private val mergedId: Long,
    private val sourceManager: SourceManager,
    private val networkToLocalManga: NetworkToLocalManga,
    private val syncChaptersWithSource: SyncChaptersWithSource,
    private val getChaptersByMangaId: GetChaptersByMangaId,
) : ViewModel() {

    private val repository = MergedMangaRepository()
    private val manager = MergedMangaManager(sourceManager)

    private var activeMergedId: Long = mergedId

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _openReader = MutableSharedFlow<OpenReader>(extraBufferCapacity = 1)
    val openReader: SharedFlow<OpenReader> = _openReader.asSharedFlow()

    init {
        load(mergedId)
    }

    private fun load(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            activeMergedId = id
            var manga = repository.getMergedMangaById(id)
            val refs = repository.getReferences(id)
            val chapters = repository.getChapters(id)
            _state.update {
                it.copy(
                    manga = manga,
                    references = refs,
                    allChapters = chapters,
                    isLoading = false,
                ).withFilteredChapters()
            }

            if (manga != null && manga.scanlationGroups.isNullOrBlank()) {
                try {
                    val groups = ScanlationFetcher().fetchGroupsForTitle(manga.title)
                    if (!groups.isNullOrBlank()) {
                        repository.updateScanlationGroups(id, groups)
                        manga = repository.getMergedMangaById(id)
                        _state.update { it.copy(manga = manga) }
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    fun setLanguageFilter(filter: String) {
        _state.update {
            it.copy(languageFilter = filter).withFilteredChapters()
        }
    }

    fun setPaidFilter(filter: PaidFilter) {
        _state.update {
            it.copy(paidFilter = filter).withFilteredChapters()
        }
    }

    fun toggleLanguagePanel() {
        LanguagePanelState.expanded = !LanguagePanelState.expanded
        _state.update { it.copy(languagePanelExpanded = LanguagePanelState.expanded) }
    }

    fun togglePaidPanel() {
        PaidPanelState.expanded = !PaidPanelState.expanded
        _state.update { it.copy(paidPanelExpanded = PaidPanelState.expanded) }
    }

    fun relink() {
        val manga = _state.value.manga ?: return
        if (_state.value.isRelinking) return
        _state.update {
            it.copy(isRelinking = true, statusText = "Searching all extensions…")
        }
        viewModelScope.launch {
            try {
                val newId = withContext(Dispatchers.IO) {
                    manager.createOrUpdateMergedManga(
                        title = manga.title,
                        coverUrl = manga.coverUrl,
                        synopsis = manga.synopsis,
                        author = manga.author,
                        malId = manga.malId,
                    )
                }
                val idToUse = if (newId > 0) newId else activeMergedId
                activeMergedId = idToUse
                val updated = withContext(Dispatchers.IO) {
                    repository.getMergedMangaById(idToUse)
                }
                val refs = withContext(Dispatchers.IO) {
                    repository.getReferences(idToUse)
                }
                val chapters = withContext(Dispatchers.IO) {
                    repository.getChapters(idToUse)
                }
                _state.update {
                    it.copy(
                        isRelinking = false,
                        manga = updated ?: manga,
                        references = refs,
                        allChapters = chapters,
                        statusText = "Done. Sources: ${refs.size}",
                    ).withFilteredChapters()
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

        val id = activeMergedId
        _state.update {
            it.copy(
                isFetchingChapters = true,
                statusText = "Fetching chapters from ${it.references.size} sources…",
            )
        }

        viewModelScope.launch {
            try {
                val fetched = withContext(Dispatchers.IO) {
                    fetchChaptersFromSources(
                        references = current.references,
                        mergedId = id,
                    )
                }
                withContext(Dispatchers.IO) {
                    repository.addChapters(fetched)
                    current.references.forEach { ref ->
                        val count = fetched.count { it.sourceId == ref.sourceId }
                        if (count > 0) {
                            repository.updateReferenceChapterCount(
                                mergedId = id,
                                sourceId = ref.sourceId,
                                mangaUrl = ref.mangaUrl,
                                count = count,
                            )
                        }
                    }
                    // Vision: fill author / genres / status from best source
                    fillDetailsFromBestSource(current.references)
                }
                val chapters = withContext(Dispatchers.IO) { repository.getChapters(id) }
                val refs = withContext(Dispatchers.IO) { repository.getReferences(id) }
                val manga = withContext(Dispatchers.IO) { repository.getMergedMangaById(id) }
                _state.update {
                    val next = it.copy(
                        isFetchingChapters = false,
                        allChapters = chapters,
                        references = refs,
                        manga = manga ?: it.manga,
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

    private suspend fun fillDetailsFromBestSource(references: List<MergedMangaReference>) {
        val sorted = references.sortedByDescending { it.priority }
        for (ref in sorted.take(3)) {
            try {
                val source = sourceManager.get(ref.sourceId) ?: continue
                val sManga = SManga.create().apply {
                    url = ref.mangaUrl
                    title = ref.mangaTitle ?: ""
                }
                val updated = withTimeoutOrNull(12_000) {
                    source.getMangaUpdate(
                        manga = sManga,
                        chapters = emptyList(),
                        fetchDetails = true,
                        fetchChapters = false,
                    )
                } ?: continue

                val detail = updated.manga
                val genreStr = detail.genre
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?.joinToString(", ")
                    ?.ifBlank { null }

                val statusStr = when (detail.status.toInt()) {
                    SManga.ONGOING -> "Ongoing"
                    SManga.COMPLETED -> "Completed"
                    SManga.LICENSED -> "Licensed"
                    SManga.PUBLISHING_FINISHED -> "Publishing finished"
                    SManga.CANCELLED -> "Cancelled"
                    SManga.ON_HIATUS -> "On hiatus"
                    else -> null
                }

                repository.updateDetailsIfBlank(
                    mergedId = activeMergedId,
                    author = detail.author,
                    artist = detail.artist,
                    status = statusStr,
                    genres = genreStr,
                    synopsis = detail.description,
                    coverUrl = detail.thumbnail_url,
                )
                // Stop after first source that gave useful metadata
                if (!detail.author.isNullOrBlank() || !detail.description.isNullOrBlank()) {
                    break
                }
            } catch (_: Exception) {
            }
        }
    }

    fun openChapter(mergedChapter: MergedChapter) {
        viewModelScope.launch {
            try {
                _state.update { it.copy(statusText = "Opening reader…") }

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

                val reader = withContext(Dispatchers.IO) {
                    val localManga = networkToLocalManga(sManga.toDomainManga(source.id))

                    val remoteChapters: List<SChapter> = withTimeoutOrNull(25_000) {
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
                            syncChaptersWithSource.await(
                                rawSourceChapters = remoteChapters,
                                manga = localManga,
                                source = source,
                                manualFetch = true,
                            )
                        } catch (_: Exception) {
                        }
                    }

                    val dbChapters = getChaptersByMangaId.await(localManga.id)
                    val matched = findBestChapter(dbChapters, mergedChapter, remoteChapters)

                    if (matched != null) {
                        OpenReader(mangaId = localManga.id, chapterId = matched.id)
                    } else {
                        null
                    }
                }

                if (reader == null) {
                    _state.update {
                        it.copy(
                            statusText = "Could not open chapter. Try Fetch chapters, then again.",
                        )
                    }
                    return@launch
                }

                _openReader.emit(reader)
                _state.update { it.copy(statusText = "") }
            } catch (e: Exception) {
                _state.update { it.copy(statusText = "Open error: ${e.message}") }
            }
        }
    }

    private fun findBestChapter(
        dbChapters: List<Chapter>,
        merged: MergedChapter,
        remote: List<SChapter>,
    ): Chapter? {
        if (dbChapters.isEmpty()) return null

        dbChapters.find { it.url == merged.url }?.let { return it }

        val tail = merged.url.substringAfterLast('/')
        if (tail.isNotBlank()) {
            dbChapters.find {
                it.url.endsWith(tail) || it.url.contains(tail)
            }?.let { return it }
        }

        remote.find { it.url == merged.url }?.let { sc ->
            dbChapters.find { it.url == sc.url }?.let { return it }
        }

        val targetNum = chapterNumberOf(merged)
        if (targetNum >= 0f) {
            val byNum = dbChapters.filter {
                abs(it.chapterNumber - targetNum.toDouble()) < 0.001
            }
            if (byNum.size == 1) return byNum.first()
            if (byNum.isNotEmpty()) {
                byNum.find {
                    it.name.contains(merged.name.take(12), ignoreCase = true) ||
                        merged.name.contains(it.name.take(12), ignoreCase = true)
                }?.let { return it }
                return byNum.first()
            }
        }

        dbChapters.find {
            it.name.equals(merged.name, ignoreCase = true)
        }?.let { return it }

        val clean = merged.name.lowercase().trim()
        dbChapters.find {
            val n = it.name.lowercase().trim()
            n == clean || n.contains(clean) || clean.contains(n)
        }?.let { return it }

        return null
    }

    private suspend fun fetchChaptersFromSources(
        references: List<MergedMangaReference>,
        mergedId: Long,
    ): List<MergedChapter> = coroutineScope {
        val deferred = references.map { ref ->
            async {
                try {
                    val source: Source = sourceManager.get(ref.sourceId)
                        ?: return@async emptyList<MergedChapter>()

                    val sManga = SManga.create().apply {
                        url = ref.mangaUrl
                        title = ref.mangaTitle ?: ""
                    }

                    val chapterList: List<SChapter> = withTimeoutOrNull(20_000) {
                        val update = source.getMangaUpdate(
                            manga = sManga,
                            chapters = emptyList(),
                            fetchDetails = false,
                            fetchChapters = true,
                        )
                        update.chapters
                    } ?: return@async emptyList<MergedChapter>()

                    chapterList.map { ch ->
                        val detected = detectLanguage(ch.name, source.lang)
                        MergedChapter(
                            mergedId = mergedId,
                            sourceId = ref.sourceId,
                            url = ch.url,
                            name = ch.name,
                            chapterNumber = ch.chapter_number,
                            language = detected,
                            dateUpload = ch.date_upload,
                        )
                    }
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }

        deferred.awaitAll()
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

    private fun volumeNumberOf(ch: MergedChapter): Int {
        val name = ch.name
        val lower = name.lowercase(Locale.ROOT)
        val markers = listOf("vol.", "vol ", "volume ", "v.")
        for (marker in markers) {
            val idx = lower.indexOf(marker)
            if (idx >= 0) {
                var i = idx + marker.length
                while (i < name.length && (name[i].isWhitespace() || name[i] == '.')) {
                    i++
                }
                val num = buildString {
                    while (i < name.length && name[i].isDigit()) {
                        append(name[i])
                        i++
                    }
                }
                num.toIntOrNull()?.let { return it }
            }
        }
        return -1
    }

    private fun chapterNumberOf(ch: MergedChapter): Float {
        if (ch.chapterNumber > 0f) return ch.chapterNumber

        val name = ch.name
        val lower = name.lowercase(Locale.ROOT)
        val markers = listOf("chapter", "ch.", "ch ", "c.")

        for (marker in markers) {
            val idx = lower.indexOf(marker)
            if (idx >= 0) {
                var i = idx + marker.length
                while (i < name.length && (name[i] == '.' || name[i].isWhitespace())) {
                    i++
                }
                val num = buildString {
                    while (i < name.length) {
                        val c = name[i]
                        if (c.isDigit() || c == '.') {
                            append(c)
                            i++
                        } else {
                            break
                        }
                    }
                }
                num.toFloatOrNull()?.let { return it }
            }
        }

        var i = 0
        while (i < name.length && name[i].isWhitespace()) {
            i++
        }
        val leading = buildString {
            while (i < name.length) {
                val c = name[i]
                if (c.isDigit() || c == '.') {
                    append(c)
                    i++
                } else {
                    break
                }
            }
        }
        return leading.toFloatOrNull() ?: -1f
    }

    private fun detectLanguage(chapterName: String, sourceLang: String?): String {
        val bracket = Regex("""^\s*\[([a-zA-Z]{2}(?:-[a-zA-Z]{2})?)\]""").find(chapterName)
        if (bracket != null) {
            return bracket.groupValues[1].lowercase(Locale.ROOT)
        }
        val paren = Regex("""^\s*\(([a-zA-Z]{2}(?:-[a-zA-Z]{2})?)\)""").find(chapterName)
        if (paren != null) {
            return paren.groupValues[1].lowercase(Locale.ROOT)
        }
        return sourceLang?.lowercase(Locale.ROOT)?.trim().orEmpty()
    }

    private fun resolvedLang(ch: MergedChapter): String {
        val fromName = detectLanguage(ch.name, null)
        if (fromName.isNotEmpty()) return fromName
        return ch.language?.lowercase(Locale.ROOT)?.trim().orEmpty()
    }

    private fun isPaidChapter(
        ch: MergedChapter,
        refs: List<MergedMangaReference>,
    ): Boolean {
        val name = ch.name.lowercase(Locale.ROOT)
        if (name.contains("🔒") || name.contains("lock") || name.contains("paid")) {
            return true
        }
        val sourceName = refs.find { it.sourceId == ch.sourceId }
            ?.sourceName
            ?.lowercase(Locale.ROOT)
            .orEmpty()
        val paidMarkers = listOf(
            "k manga",
            "kmanga",
            "kodansha",
            "shonen jump",
            "viz",
            "manga plus",
            "mangaplus",
            "crunchyroll",
            "comic walker paid",
        )
        return paidMarkers.any { sourceName.contains(it) }
    }

    private fun State.withFilteredChapters(): State {
        val refPriority = references.associate { it.sourceId to it.priority }
        val refChapterCount = references.associate { it.sourceId to it.chapterCount }

        val filter = languageFilter.trim().lowercase(Locale.ROOT)
        val languageFiltered = when (filter) {
            "all", "" -> allChapters
            "en", "eng", "english", "gb" -> {
                allChapters.filter { ch ->
                    val lang = resolvedLang(ch)
                    lang.isEmpty() ||
                        lang == "en" ||
                        lang == "gb" ||
                        lang == "eng" ||
                        lang.startsWith("en")
                }
            }
            else -> {
                allChapters.filter { ch ->
                    val lang = resolvedLang(ch)
                    lang == filter || lang.startsWith(filter)
                }
            }
        }

        val paidAware = when (paidFilter) {
            PaidFilter.FREE -> languageFiltered.filter { !isPaidChapter(it, references) }
            PaidFilter.PAID -> languageFiltered.filter { isPaidChapter(it, references) }
            PaidFilter.ALL -> languageFiltered
        }

        val grouped = paidAware.groupBy { ch ->
            val vol = volumeNumberOf(ch)
            val num = chapterNumberOf(ch)
            when {
                vol >= 0 && num >= 0f -> "v:$vol|n:$num"
                num >= 0f -> "n:$num"
                else -> "t:" + ch.name.trim().lowercase(Locale.ROOT)
            }
        }

        val unique = grouped.values.map { group ->
            group.sortedWith(
                compareByDescending<MergedChapter> { ch ->
                    val lang = resolvedLang(ch)
                    when {
                        lang == "en" || lang == "gb" || lang.isEmpty() || lang.startsWith("en") -> 3
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
            compareBy<MergedChapter> { ch ->
                val v = volumeNumberOf(ch)
                if (v < 0) Int.MAX_VALUE else v
            }.thenBy { ch ->
                val n = chapterNumberOf(ch)
                if (n < 0f) Float.MAX_VALUE else n
            }.thenBy { ch ->
                ch.name.lowercase(Locale.ROOT)
            },
        )

        val languages = allChapters
            .map { resolvedLang(it) }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()

        val paidCount = allChapters.count { isPaidChapter(it, references) }

        return copy(
            displayChapters = unique,
            availableLanguages = languages,
            languagePanelExpanded = LanguagePanelState.expanded,
            paidPanelExpanded = PaidPanelState.expanded,
            paidChapterCount = paidCount,
        )
    }

    enum class PaidFilter {
        FREE,
        PAID,
        ALL,
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
        val languagePanelExpanded: Boolean = LanguagePanelState.expanded,
        val paidFilter: PaidFilter = PaidFilter.FREE,
        val paidPanelExpanded: Boolean = PaidPanelState.expanded,
        val paidChapterCount: Int = 0,
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

object LanguagePanelState {
    @Volatile
    var expanded: Boolean = false
}

object PaidPanelState {
    @Volatile
    var expanded: Boolean = false
}
