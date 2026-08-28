package eu.kanade.tachiyomi.data.discovery

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SyncProgress(
    val isRunning: Boolean = false,
    val percentage: Int = 0,
    val message: String = "",
)

object DiscoveryProgressState {
    private val _progress = MutableStateFlow(SyncProgress())
    val progress = _progress.asStateFlow()

    fun update(isRunning: Boolean, percentage: Int, message: String) {
        _progress.value = SyncProgress(
            isRunning = isRunning,
            percentage = percentage.coerceIn(0, 100),
            message = message,
        )
    }

    fun reset() {
        _progress.value = SyncProgress(isRunning = false, percentage = 0, message = "")
    }
}
