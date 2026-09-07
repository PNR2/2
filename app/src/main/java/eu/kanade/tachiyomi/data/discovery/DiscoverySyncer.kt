@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.data.discovery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DiscoverySyncer {

    private val repository = MergedMangaRepository()
    private val malFetcher = MalDiscoveryFetcher()
    private val malRepository = MalDiscoveryRepository()
    private val rssRepository = RssNewsRepository()

    suspend fun syncNow() = withContext(Dispatchers.IO) {
        try {
            val seasonal = malFetcher.fetchSeasonalManga()
            malRepository.insertOrUpdateSeasonal(seasonal)
        } catch (_: Exception) {
        }
        try {
            rssRepository.refreshFromSources()
        } catch (_: Exception) {
        }
    }

    suspend fun linkTitleToMerged(
        title: String,
        coverUrl: String? = null,
        synopsis: String? = null,
        author: String? = null,
        malId: Long? = null,
    ): Long = withContext(Dispatchers.IO) {
        repository.createOrUpdateMergedManga(
            title = title,
            coverUrl = coverUrl,
            synopsis = synopsis,
            author = author,
            malId = malId,
        )
    }

    fun getMerged(id: Long): MergedManga? {
        return repository.getMergedMangaById(id)
    }

    fun getReferences(mergedId: Long): List<MergedMangaReference> {
        return repository.getReferences(mergedId)
    }
}
