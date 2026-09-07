@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.data.discovery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DiscoverySyncer {

    private val repository = MergedMangaRepository()

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
