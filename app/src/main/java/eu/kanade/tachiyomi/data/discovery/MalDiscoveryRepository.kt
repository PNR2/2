@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.data.discovery

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

// NEW: Our custom sorting options
enum class DiscoverySort {
    LATEST,
    SCORE,
    TITLE,
}

class MalDiscoveryRepository {

    companion object {
        // Changed to Application to satisfy the database helper!
        private val application: Application = Injekt.get()
        private val dbHelper = DiscoveryDatabaseHelper(application)

        // NEW: Native SharedPreferences to store the ON/OFF toggle
        private val prefs = application.getSharedPreferences("discovery_prefs", Context.MODE_PRIVATE)

        private val mangaFlowState = MutableStateFlow<List<MalDiscoveryItem>>(emptyList())
        private var currentSort = DiscoverySort.LATEST

        init {
            refreshFlow()
        }

        // NEW: Toggle Settings logic
        fun isAutomationEnabled(): Boolean {
            return prefs.getBoolean("automation_enabled", true)
        }

        fun setAutomationEnabled(enabled: Boolean) {
            prefs.edit().putBoolean("automation_enabled", enabled).apply()
        }

        // NEW: Sorting logic
        fun setSortMethod(sort: DiscoverySort) {
            currentSort = sort
            refreshFlow()
        }

        fun refreshFlow() {
            val db = dbHelper.readableDatabase

            // dynamically change the SQL query based on user preference
            val orderBy = when (currentSort) {
                DiscoverySort.LATEST -> "start_date DESC"
                DiscoverySort.SCORE -> "score DESC"
                DiscoverySort.TITLE -> "title ASC"
            }

            val cursor = db.rawQuery(
                "SELECT * FROM mal_discovery_entry WHERE is_seasonal = 1 ORDER BY $orderBy",
                null,
            )
            val list = mutableListOf<MalDiscoveryItem>()

            if (cursor.moveToFirst()) {
                do {
                    val scoreIdx = cursor.getColumnIndexOrThrow("score")
                    val sourceIdIdx = cursor.getColumnIndexOrThrow("source_id")
                    val mangaUrlIdx = cursor.getColumnIndexOrThrow("manga_url")

                    list.add(
                        MalDiscoveryItem(
                            malId = cursor.getLong(cursor.getColumnIndexOrThrow("mal_id")),
                            title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
                            coverUrl = cursor.getString(cursor.getColumnIndexOrThrow("cover_url")),
                            synopsis = cursor.getString(cursor.getColumnIndexOrThrow("synopsis")),
                            score = if (cursor.isNull(scoreIdx)) null else cursor.getDouble(scoreIdx),
                            startDate = cursor.getString(cursor.getColumnIndexOrThrow("start_date")),
                            isSeasonal = cursor.getInt(cursor.getColumnIndexOrThrow("is_seasonal")) == 1,
                            sourceId = if (cursor.isNull(sourceIdIdx)) null else cursor.getLong(sourceIdIdx),
                            mangaUrl = if (cursor.isNull(mangaUrlIdx)) null else cursor.getString(mangaUrlIdx),
                        ),
                    )
                } while (cursor.moveToNext())
            }
            cursor.close()
            mangaFlowState.value = list
        }
    }

    suspend fun insertSeasonalManga(mangaList: List<MalDiscoveryItem>) {
        val db = dbHelper.writableDatabase
        val currentTime = System.currentTimeMillis()
        db.beginTransaction()
        try {
            mangaList.forEach { manga ->
                val values = ContentValues().apply {
                    put("mal_id", manga.malId)
                    put("title", manga.title)
                    put("cover_url", manga.coverUrl)
                    put("synopsis", manga.synopsis)
                    put("score", manga.score)
                    put("start_date", manga.startDate)
                    put("is_seasonal", if (manga.isSeasonal) 1 else 0)
                    put("last_synced", currentTime)
                    put("source_id", manga.sourceId)
                    put("manga_url", manga.mangaUrl)
                }
                db.insertWithOnConflict(
                    "mal_discovery_entry",
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        refreshFlow()
    }

    fun subscribeToSeasonalManga(): StateFlow<List<MalDiscoveryItem>> {
        return mangaFlowState.asStateFlow()
    }
}
