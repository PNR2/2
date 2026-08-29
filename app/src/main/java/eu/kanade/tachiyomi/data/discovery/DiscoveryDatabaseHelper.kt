package eu.kanade.tachiyomi.data.discovery

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class DiscoveryDatabaseHelper(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS mal_discovery_entry (
                mal_id INTEGER PRIMARY KEY,
                title TEXT NOT NULL,
                cover_url TEXT,
                synopsis TEXT,
                score REAL,
                start_date TEXT,
                is_seasonal INTEGER NOT NULL DEFAULT 1,
                last_synced INTEGER,
                source_id INTEGER,
                manga_url TEXT,
                chapters INTEGER,
                status TEXT,
                authors TEXT,
                genres TEXT
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS rss_news_entry (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                link TEXT,
                description TEXT,
                pub_date TEXT,
                source_name TEXT,
                last_synced INTEGER
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // Add the new columns safely
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

    companion object {
        private const val DATABASE_NAME = "discovery.db"
        private const val DATABASE_VERSION = 2
    }
}
