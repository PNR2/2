@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.data.discovery

import android.app.Application
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class DiscoveryDatabaseHelper(
    app: Application,
) : SQLiteOpenHelper(app, "musyomi_discovery.db", null, 3) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS rss_news_article (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "title TEXT NOT NULL, " +
                "link TEXT UNIQUE NOT NULL, " +
                "description TEXT, " +
                "image_url TEXT, " +
                "publication_date INTEGER NOT NULL, " +
                "source_name TEXT NOT NULL, " +
                "is_read INTEGER DEFAULT 0)",
        )

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS mal_discovery_entry (" +
                "mal_id INTEGER PRIMARY KEY, " +
                "title TEXT NOT NULL, " +
                "cover_url TEXT, " +
                "synopsis TEXT, " +
                "score REAL, " +
                "start_date TEXT, " +
                "is_seasonal INTEGER DEFAULT 1, " +
                "last_synced INTEGER NOT NULL, " +
                "source_id INTEGER, " +
                "manga_url TEXT, " +
                "chapters INTEGER, " +
                "status TEXT, " +
                "authors TEXT, " +
                "genres TEXT)",
        )
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int,
    ) {
        // Safe upgrade – add missing columns if they do not exist
        if (oldVersion < 3) {
            try {
                db.execSQL("ALTER TABLE mal_discovery_entry ADD COLUMN chapters INTEGER")
            } catch (_: Exception) {
            }
            try {
                db.execSQL("ALTER TABLE mal_discovery_entry ADD COLUMN status TEXT")
            } catch (_: Exception) {
            }
            try {
                db.execSQL("ALTER TABLE mal_discovery_entry ADD COLUMN authors TEXT")
            } catch (_: Exception) {
            }
            try {
                db.execSQL("ALTER TABLE mal_discovery_entry ADD COLUMN genres TEXT")
            } catch (_: Exception) {
            }
        }
    }
}

class RssNewsRepository {

    companion object {
        private val dbHelper = DiscoveryDatabaseHelper(Injekt.get())
        private val newsFlowState = MutableStateFlow<List<RssNewsItem>>(emptyList())

        init {
            refreshFlow()
        }

        fun refreshFlow() {
            val db = dbHelper.readableDatabase
            val cursor = db.rawQuery(
                "SELECT * FROM rss_news_article ORDER BY publication_date DESC",
                null,
            )
            val list = mutableListOf<RssNewsItem>()

            if (cursor.moveToFirst()) {
                do {
                    list.add(
                        RssNewsItem(
                            title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
                            link = cursor.getString(cursor.getColumnIndexOrThrow("link")),
                            description = cursor.getString(
                                cursor.getColumnIndexOrThrow("description"),
                            ),
                            imageUrl = cursor.getString(
                                cursor.getColumnIndexOrThrow("image_url"),
                            ),
                            publicationDate = cursor.getLong(
                                cursor.getColumnIndexOrThrow("publication_date"),
                            ),
                            sourceName = cursor.getString(
                                cursor.getColumnIndexOrThrow("source_name"),
                            ),
                        ),
                    )
                } while (cursor.moveToNext())
            }
            cursor.close()
            newsFlowState.value = list
        }
    }

    suspend fun insertNews(articles: List<RssNewsItem>) {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            articles.forEach { article ->
                val values = ContentValues().apply {
                    put("title", article.title)
                    put("link", article.link)
                    put("description", article.description)
                    put("image_url", article.imageUrl)
                    put("publication_date", article.publicationDate)
                    put("source_name", article.sourceName)
                    put("is_read", 0)
                }
                db.insertWithOnConflict(
                    "rss_news_article",
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

    fun subscribeToNews(): StateFlow<List<RssNewsItem>> {
        return newsFlowState.asStateFlow()
    }
}
