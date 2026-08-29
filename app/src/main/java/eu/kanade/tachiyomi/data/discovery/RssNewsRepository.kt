@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.data.discovery

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

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
