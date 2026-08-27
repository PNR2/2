@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.data.discovery

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class MalDiscoveryRepository {

    companion object {
        private val dbHelper = DiscoveryDatabaseHelper(Injekt.get())
        private val mangaFlowState = MutableStateFlow<List<MalDiscoveryItem>>(emptyList())

        init {
            refreshFlow()
        }

        fun refreshFlow() {
            val db = dbHelper.readableDatabase
            val cursor = db.rawQuery(
                "SELECT * FROM mal_discovery_entry WHERE is_seasonal = 1 ORDER BY start_date DESC",
                null,
            )
            val list = mutableListOf<MalDiscoveryItem>()

            if (cursor.moveToFirst()) {
                do {
                    val scoreIndex = cursor.getColumnIndexOrThrow("score")
                    list.add(
                        MalDiscoveryItem(
                            malId = cursor.getLong(cursor.getColumnIndexOrThrow("mal_id")),
                            title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
                            coverUrl = cursor.getString(cursor.getColumnIndexOrThrow("cover_url")),
                            synopsis = cursor.getString(cursor.getColumnIndexOrThrow("synopsis")),
                            score = if (cursor.isNull(scoreIndex)) null else cursor.getDouble(scoreIndex),
                            startDate = cursor.getString(cursor.getColumnIndexOrThrow("start_date")),
                            isSeasonal = cursor.getInt(cursor.getColumnIndexOrThrow("is_seasonal")) == 1,
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
