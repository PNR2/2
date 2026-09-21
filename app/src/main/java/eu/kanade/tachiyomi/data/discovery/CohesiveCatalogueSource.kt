package eu.kanade.tachiyomi.data.discovery

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import rx.Observable

/**
 * The Phantom Source. Injects Cohesive Manga directly into Mihon's native Global Search.
 */
class CohesiveCatalogueSource(
    private val mergedManager: MergedMangaManager,
) : CatalogueSource {

    private val repository = MergedMangaRepository()

    override val id: Long = 696969L
    override val name: String = "Cohesive Manga"
    override val lang: String = "multi"
    override val supportsLatest: Boolean = false

    // CRITICAL FIX: Stops the ugly class path from showing in the UI
    override fun toString(): String = name

    override suspend fun getPopularManga(page: Int): MangasPage {
        return MangasPage(emptyList(), false)
    }

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isBlank()) return MangasPage(emptyList(), false)

        val outcome = mergedManager.searchCohesive(query = query)
        val results = mutableListOf<SManga>()

        if (outcome.primaryId > 0) {
            val primary = SManga.create().apply {
                title = outcome.primaryTitle
                url = outcome.primaryId.toString()
                initialized = false
            }
            results.add(primary)
            MergedMangaManager.ensureBackground(mergedManager, query)
        }

        outcome.similar.forEach { similarItem ->
            val similar = SManga.create().apply {
                title = similarItem.title
                thumbnail_url = similarItem.coverUrl
                url = similarItem.id.toString()
                initialized = false
            }
            results.add(similar)
        }

        return MangasPage(results, false)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        return MangasPage(emptyList(), false)
    }

    override fun getFilterList(): FilterList {
        return FilterList()
    }

    @Suppress("DEPRECATION")
    @Deprecated("Use the 1.x API instead")
    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        return Observable.fromCallable {
            val dbId = manga.url.toLongOrNull()
            
            // DIAGNOSTIC 1: Did the ID pass correctly?
            if (dbId == null) {
                manga.description = "⚙️ ERROR: URL '${manga.url}' is not a valid Database ID."
                manga.initialized = true
                return@fromCallable manga
            }

            val merged = repository.getMergedMangaById(dbId)
            
            // DIAGNOSTIC 2: Did the Manager actually save it to SQLite?
            if (merged == null) {
                manga.description = "⚙️ ERROR: Database ID $dbId not found in SQLite. Manager failed to save it."
                manga.initialized = true
                return@fromCallable manga
            }

            manga.apply {
                title = merged.title
                author = merged.author ?: "Unknown Author"
                artist = merged.artist ?: "Unknown Artist"
                description = merged.synopsis ?: "Fetching cohesive metadata in the background..."
                genre = merged.genres
                status = when (merged.status?.lowercase()) {
                    "ongoing" -> SManga.ONGOING
                    "completed" -> SManga.COMPLETED
                    "cancelled" -> SManga.CANCELLED
                    "on hiatus" -> SManga.ON_HIATUS
                    else -> SManga.UNKNOWN
                }
                thumbnail_url = merged.coverUrl ?: merged.allCovers().firstOrNull() ?: manga.thumbnail_url
                initialized = true
            }
            manga
        }
    }

    @Suppress("DEPRECATION")
    @Deprecated("Use the 1.x API instead")
    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        return Observable.fromCallable {
            val dbId = manga.url.toLongOrNull() ?: return@fromCallable emptyList<SChapter>()
            val dbChapters = repository.getChapters(dbId)
            
            // DIAGNOSTIC 3: Show a visual indicator that the app is trying
            if (dbChapters.isEmpty()) {
                val dummy = SChapter.create().apply {
                    url = "dummy"
                    name = "⚙️ Background harvester running... Pull to refresh soon."
                    chapter_number = -1f
                }
                return@fromCallable listOf(dummy)
            }

            dbChapters.map { ch ->
                SChapter.create().apply {
                    url = ch.url
                    name = ch.name
                    chapter_number = ch.chapterNumber
                    date_upload = ch.dateUpload
                    scanlator = ch.language ?: "Multi"
                }
            }.sortedByDescending { it.chapter_number }
        }
    }

    @Suppress("DEPRECATION")
    @Deprecated("Use the 1.x API instead")
    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        return Observable.just(emptyList())
    }
}
