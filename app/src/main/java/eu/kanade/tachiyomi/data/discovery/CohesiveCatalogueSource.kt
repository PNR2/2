package eu.kanade.tachiyomi.data.discovery

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Request
import okhttp3.Response
import rx.Observable

/**
 * The Phantom Source. Injects Cohesive Manga directly into Mihon's native Global Search.
 * Now extends HttpSource so Mihon's ReaderActivity natively accepts and routes page requests!
 */
class CohesiveCatalogueSource(
    private val mergedManager: MergedMangaManager,
) : HttpSource() {

    private val repository = MergedMangaRepository()

    override val id: Long = 696969L
    override val name: String = "Cohesive Manga"
    override val lang: String = "multi"
    override val supportsLatest: Boolean = false
    override val baseUrl: String = "https://cohesive.local"

    override fun toString(): String = name

    override suspend fun getPopularManga(page: Int): MangasPage = MangasPage(emptyList(), false)

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
        }

        return MangasPage(results, false)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = MangasPage(emptyList(), false)

    override fun getFilterList(): FilterList = FilterList()

    // --- MODERN SUSPEND API (Used by modern Mihon UI & Reader) ---

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val dbId = manga.url.toLongOrNull() ?: return manga
        val merged = repository.getMergedMangaById(dbId) ?: return manga

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
        return manga
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        val dbId = manga.url.toLongOrNull() ?: return emptyList()
        val dbChapters = repository.getChapters(dbId)

        if (dbChapters.isEmpty()) {
            return listOf(
                SChapter.create().apply {
                    url = "dummy"
                    name = "⚙️ Background harvester running... Pull to refresh soon."
                    chapter_number = -1f
                },
            )
        }

        return dbChapters.map { ch ->
            SChapter.create().apply {
                url = "${ch.sourceId}::||::${ch.url}"
                name = ch.name
                chapter_number = ch.chapterNumber
                date_upload = ch.dateUpload
                scanlator = ch.language ?: "Multi"
            }
        }.sortedByDescending { it.chapter_number }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val parts = chapter.url.split("::||::")
        if (parts.size != 2) throw Exception("Invalid merged chapter URL format")

        val sourceId = parts[0].toLongOrNull() ?: throw Exception("Invalid Source ID")
        val originalUrl = parts[1]

        val originalSource = mergedManager.sourceManager.get(sourceId) as? HttpSource
            ?: throw Exception("Original source extension not found or uninstalled.")

        val originalChapter = SChapter.create().apply { url = originalUrl }
        return originalSource.getPageList(originalChapter)
    }

    // --- LEGACY RXJAVA API (Required overrides to satisfy the compiler) ---

    @Suppress("DEPRECATION")
    @Deprecated("Use the 1.x API instead")
    override fun fetchMangaDetails(manga: SManga): Observable<SManga> =
        Observable.fromCallable { kotlinx.coroutines.runBlocking { getMangaDetails(manga) } }

    @Suppress("DEPRECATION")
    @Deprecated("Use the 1.x API instead")
    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> =
        Observable.fromCallable { kotlinx.coroutines.runBlocking { getChapterList(manga) } }

    @Suppress("DEPRECATION")
    @Deprecated("Use the 1.x API instead")
    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> =
        Observable.fromCallable { kotlinx.coroutines.runBlocking { getPageList(chapter) } }

    // --- DUMMY HTTP SOURCE PARSERS (Bypassed by our suspend overrides) ---
    override fun popularMangaRequest(page: Int): Request = Request.Builder().url(baseUrl).build()
    override fun popularMangaParse(response: Response): MangasPage = MangasPage(emptyList(), false)
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = Request.Builder().url(baseUrl).build()
    override fun searchMangaParse(response: Response): MangasPage = MangasPage(emptyList(), false)
    override fun latestUpdatesRequest(page: Int): Request = Request.Builder().url(baseUrl).build()
    override fun latestUpdatesParse(response: Response): MangasPage = MangasPage(emptyList(), false)
    override fun mangaDetailsParse(response: Response): SManga = SManga.create()
    override fun chapterListParse(response: Response): List<SChapter> = emptyList()
    override fun pageListParse(response: Response): List<Page> = emptyList()
    override fun imageUrlParse(response: Response): String = ""
}
