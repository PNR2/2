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
            CREATE TABLE IF NOT EXISTS mal_discovery_entry (
                mal_id INTEGER PRIMARY KEY,
                title TEXT NOT NULL,
                cover_url TEXT,
                synopsis TEXT,
                score REAL,
                start_date TEXT,
                is_seasonal INTEGER DEFAULT 1,
                last_synced INTEGER NOT NULL,
                source_id INTEGER,
                manga_url TEXT,
                chapters INTEGER,
                status TEXT,
                authors TEXT,
                genres TEXT
            )
            """.trimIndent(),
        )

        // Merged manga (cohesive entry)
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS merged_manga (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                cover_url TEXT,
                synopsis TEXT,
                author TEXT,
                artist TEXT,
                status TEXT,
                genres TEXT,
                mal_id INTEGER,
                preferred_language TEXT DEFAULT 'en',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )

        // Links between merged manga and real extension manga
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS merged_manga_reference (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                merged_id INTEGER NOT NULL,
                source_id INTEGER NOT NULL,
                manga_url TEXT NOT NULL,
                manga_title TEXT,
                chapter_count INTEGER DEFAULT 0,
                is_info_source INTEGER DEFAULT 0,
                priority INTEGER DEFAULT 0,
                UNIQUE(merged_id, source_id, manga_url)
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
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
        if (oldVersion < 4) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS merged_manga (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    title TEXT NOT NULL,
                    cover_url TEXT,
                    synopsis TEXT,
                    author TEXT,
                    artist TEXT,
                    status TEXT,
                    genres TEXT,
                    mal_id INTEGER,
                    preferred_language TEXT DEFAULT 'en',
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS merged_manga_reference (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    merged_id INTEGER NOT NULL,
                    source_id INTEGER NOT NULL,
                    manga_url TEXT NOT NULL,
                    manga_title TEXT,
                    chapter_count INTEGER DEFAULT 0,
                    is_info_source INTEGER DEFAULT 0,
                    priority INTEGER DEFAULT 0,
                    UNIQUE(merged_id, source_id, manga_url)
                )
                """.trimIndent(),
            )
        }
    }
}
