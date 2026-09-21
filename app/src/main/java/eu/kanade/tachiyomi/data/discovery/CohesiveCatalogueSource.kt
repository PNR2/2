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
            val dbId = manga.url.toLongOrNull() ?: return@fromCallable manga
            val merged = repository.getMergedMangaById(dbId) ?: return@fromCallable manga

            manga.apply {
                title = merged.title
                author = merged.author
                artist = merged.artist
                description = merged.synopsis
                genre = merged.genres
                status = when (merged.status?.lowercase()) {
                    "ongoing" -> SManga.ONGOING
                    "completed" -> SManga.COMPLETED
                    "cancelled" -> SManga.CANCELLED
                    "on hiatus" -> SManga.ON_HIATUS
                    else -> SManga.UNKNOWN
                }
                thumbnail_url = merged.coverUrl ?: merged.allCovers().firstOrNull()
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

            dbChapters.map { ch ->
                SChapter.create().apply {
                    url = ch.url
                    name = ch.name
                    chapter_number = ch.chapterNumber
                    date_upload = ch.dateUpload
                    // Use scanlator field to show which language/source this chapter belongs to
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
