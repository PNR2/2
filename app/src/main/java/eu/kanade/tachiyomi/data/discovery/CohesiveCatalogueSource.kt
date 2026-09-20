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

    // Hardcoded ID to ensure it stays consistent and doesn't conflict with real extensions
    override val id: Long = 696969L
    override val name: String = "Cohesive Manga"
    override val lang: String = "multi"
    override val supportsLatest: Boolean = false

    override suspend fun getPopularManga(page: Int): MangasPage {
        return MangasPage(emptyList(), false)
    }

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isBlank()) return MangasPage(emptyList(), false)

        // Phase 1: Fast Shallow Search via the Manager
        val outcome = mergedManager.searchCohesive(query = query)

        val results = mutableListOf<SManga>()

        // Card 1: Primary Match
        if (outcome.primaryId > 0) {
            val primary = SManga.create().apply {
                title = outcome.primaryTitle
                url = outcome.primaryId.toString() // We pass the DB ID as the URL
                initialized = false
            }
            results.add(primary)

            // Phase 2: Intent-based auto-harvest (Triggers in background)
            MergedMangaManager.ensureBackground(mergedManager, query)
        }

        // Cards 2+: Similar Matches
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
        // Will map SQLite merged_manga details here in the next vertical
        return Observable.just(manga)
    }

    @Suppress("DEPRECATION")
    @Deprecated("Use the 1.x API instead")
    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        // Will stream SQLite merged_chapter lists here in the next vertical
        return Observable.just(emptyList())
    }

    @Suppress("DEPRECATION")
    @Deprecated("Use the 1.x API instead")
    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        return Observable.just(emptyList())
    }
}
