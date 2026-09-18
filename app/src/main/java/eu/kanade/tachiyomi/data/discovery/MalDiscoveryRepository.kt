@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.data.discovery

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class MalDiscoveryRepository {

    companion object {
        // Enforcing Rule 11: This strictly holds only the current filter's results.
        private val _seasonalMangaFlow = MutableStateFlow<List<MalDiscoveryItem>>(emptyList())

        private var isAutomationOn = false
        private var currentSort = DiscoverySort.SCORE

        fun isAutomationEnabled(): Boolean = isAutomationOn

        fun setAutomationEnabled(enabled: Boolean) {
            isAutomationOn = enabled
        }

        fun getSortMethod(): DiscoverySort = currentSort

        fun setSortMethod(sort: DiscoverySort) {
            currentSort = sort
            // Instantly re-sort the UI without needing to re-fetch from the network
            val currentList = _seasonalMangaFlow.value
            _seasonalMangaFlow.value = applySortLogic(currentList, sort)
        }

        private fun applySortLogic(
            items: List<MalDiscoveryItem>,
            sort: DiscoverySort,
        ): List<MalDiscoveryItem> {
            return when (sort) {
                DiscoverySort.SCORE -> items.sortedByDescending { it.score }
                DiscoverySort.CHAPTERS -> items.sortedByDescending { it.chapters }
                DiscoverySort.TITLE -> items.sortedBy { it.title }
                DiscoverySort.LATEST -> items // Default API order (usually by start date)
            }
        }
    }

    fun subscribeToSeasonalManga(): Flow<List<MalDiscoveryItem>> {
        return _seasonalMangaFlow.asStateFlow()
    }

    fun insertSeasonalManga(items: List<MalDiscoveryItem>) {
        // Enforcing Rule 10 & 11: We completely wipe the old state.
        // If 'items' is empty, it correctly clears the UI.
        _seasonalMangaFlow.value = applySortLogic(items, currentSort)
    }
}
