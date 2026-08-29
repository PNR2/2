package eu.kanade.tachiyomi.data.discovery

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class DiscoveryDatabaseHelper(
    app: Application,
) : SQLiteOpenHelper(app, "musyomi_discovery.db", null, 4) {

    override fun onCreate(db: SQLiteDatabase) {
        // RSS News
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS rss_news_article (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                link TEXT UNIQUE NOT NULL,
                description TEXT,
                image_url TEXT,
                publication_date INTEGER NOT NULL,
                source_name TEXT NOT NULL,
                is_read INTEGER DEFAULT 0
            )
            """.trimIndent(),
        )

        // Seasonal / MAL entries
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS
