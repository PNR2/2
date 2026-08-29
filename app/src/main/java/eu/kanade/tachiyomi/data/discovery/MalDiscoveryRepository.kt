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

enum class DiscoverySort {
    LATEST,
    SCORE,
    TITLE,
}

class MalDiscoveryRepository {

    companion object {
        private val application: Application = Injekt.get()
        private val dbHelper = DiscoveryDatabaseHelper(application)
        private val prefs = application.getSharedPreferences("discovery_prefs", Context.MODE_PRIVATE)

        private val mangaFlowState = MutableStateFlow<List<MalDiscoveryItem>>(emptyList())
        private var currentSort = DiscoverySort.LATEST

        init {
            refreshFlow()
        }

        fun isAutomationEnabled(): Boolean {
            return prefs.getBoolean("automation_enabled", true)
        }

        fun setAutomationEnabled(enabled: Boolean) {
            prefs.edit().putBoolean("automation_enabled", enabled).apply()
        }

        fun setSortMethod(sort: DiscoverySort) {
            currentSort = sort
            refreshFlow()
        }

        fun refreshFlow() {
            try {
                val db = dbHelper.readableDatabase
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
                        val scoreIdx = cursor.getColumnIndex("score")
                        val sourceIdIdx = cursor.getColumnIndex("source_id")
                        val mangaUrlIdx = cursor.getColumnIndex("manga_url")
                        val chaptersIdx = cursor.getColumnIndex("chapters")
                        val statusIdx = cursor.getColumnIndex("status")
                        val authorsIdx = cursor.getColumnIndex("authors")
                        val genresIdx = cursor.getColumnIndex("genres")

                        list.add(
                            MalDiscoveryItem(
                                malId = cursor.getLong(cursor.getColumnIndexOrThrow("mal_id")),
                                title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
                                coverUrl = cursor.getString(cursor.getColumnIndexOrThrow("cover_url")),
                                synopsis = cursor.getString(cursor.getColumnIndexOrThrow("synopsis")),
                                score = if (scoreIdx >= 0 && !cursor.isNull(scoreIdx)) {
                                    cursor.getDouble(scoreIdx)
                                } else null,
                                startDate = cursor.getString(cursor.getColumnIndexOrThrow("start_date")),
                                isSeasonal = cursor.getInt(cursor.getColumnIndexOrThrow("is_seasonal")) == 1,
                                sourceId = if (sourceIdIdx >= 0 && !cursor.isNull(sourceIdIdx)) {
                                    cursor.getLong(sourceIdIdx)
                                } else null,
                                mangaUrl = if (mangaUrlIdx >= 0 && !cursor.isNull(mangaUrlIdx)) {
                                    cursor.getString(mangaUrlIdx)
                                } else null,
                                chapters = if (chaptersIdx >= 0 && !cursor.isNull(chaptersIdx)) {
                                    cursor.getInt(chaptersIdx)
                                } else null,
                                status = if (statusIdx >= 0 && !cursor.isNull(statusIdx)) {
                                    cursor.getString(statusIdx)
                                } else null,
                                authors = if (authorsIdx >= 0 && !cursor.isNull(authorsIdx)) {
                                    cursor.getString(authorsIdx)
                                } else null,
                                genres = if (genresIdx >= 0 && !cursor.isNull(genresIdx)) {
                                    cursor.getString(genresIdx)
                                } else null,
                            ),
                        )
                    } while (cursor.moveToNext())
                }
                cursor.close()
                mangaFlowState.value = list
            } catch (e: Exception) {
                mangaFlowState.value = emptyList()
            }
        }
    }

    suspend fun insertSeasonalManga(mangaList: List<MalDiscoveryItem>) {
        try {
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
                        put("chapters", manga.chapters)
                        put("status", manga.status)
                        put("authors", manga.authors)
                        put("genres", manga.genres)
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
        } catch (e: Exception) {
            // Ignore so the app does not crash
        }
    }

    /** Save the Auto-Link result for a MAL manga */
    fun saveAutoLink(malId: Long, sourceId: Long, mangaUrl: String) {
        try {
            val db = dbHelper.writableDatabase
            val values = ContentValues().apply {
                put("source_id", sourceId)
                put("manga_url", mangaUrl)
            }
            db.update(
                "mal_discovery_entry",
                values,
                "mal_id = ?",
                arrayOf(malId.toString()),
            )
            refreshFlow()
        } catch (_: Exception) {
        }
    }

    fun subscribeToSeasonalManga(): StateFlow<List<MalDiscoveryItem>> {
        return mangaFlowState.asStateFlow()
    }
}
