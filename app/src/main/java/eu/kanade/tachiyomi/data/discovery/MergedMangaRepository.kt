@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.data.discovery

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

data class MergedManga(
    val id: Long = 0,
    val title: String,
    val coverUrl: String? = null,
    val synopsis: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val status: String? = null,
    val genres: String? = null,
    val malId: Long? = null,
    val preferredLanguage: String = "en",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

data class MergedMangaReference(
    val id: Long = 0,
    val mergedId: Long,
    val sourceId: Long,
    val mangaUrl: String,
    val mangaTitle: String? = null,
    val chapterCount: Int = 0,
    val isInfoSource: Boolean = false,
    val priority: Int = 0,
    val sourceName: String? = null,
)

data class MergedChapter(
    val id: Long = 0,
    val mergedId: Long,
    val sourceId: Long,
    val url: String,
    val name: String,
    val chapterNumber: Float = -1f,
    val language: String? = null,
    val dateUpload: Long = 0,
)

class MergedMangaRepository {

    companion object {
        private val dbHelper = DiscoveryDatabaseHelper(Injekt.get())
        private val mergedFlow = MutableStateFlow<List<MergedManga>>(emptyList())

        init {
            refreshFlow()
        }

        private fun refreshFlow() {
            try {
                val db = dbHelper.readableDatabase
                val cursor = db.rawQuery(
                    "SELECT * FROM merged_manga ORDER BY updated_at DESC",
                    null,
                )
                val list = mutableListOf<MergedManga>()
                if (cursor.moveToFirst()) {
                    do {
                        list.add(readMergedManga(cursor))
                    } while (cursor.moveToNext())
                }
                cursor.close()
                mergedFlow.value = list
            } catch (_: Exception) {
            }
        }

        private fun readMergedManga(cursor: android.database.Cursor): MergedManga {
            val malIdx = cursor.getColumnIndex("mal_id")
            return MergedManga(
                id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
                coverUrl = cursor.getString(cursor.getColumnIndexOrThrow("cover_url")),
                synopsis = cursor.getString(cursor.getColumnIndexOrThrow("synopsis")),
                author = cursor.getString(cursor.getColumnIndexOrThrow("author")),
                artist = cursor.getString(cursor.getColumnIndexOrThrow("artist")),
                status = cursor.getString(cursor.getColumnIndexOrThrow("status")),
                genres = cursor.getString(cursor.getColumnIndexOrThrow("genres")),
                malId = if (malIdx >= 0 && !cursor.isNull(malIdx)) cursor.getLong(malIdx) else null,
                preferredLanguage = cursor.getString(
                    cursor.getColumnIndexOrThrow("preferred_language"),
                ) ?: "en",
                createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")),
            )
        }
    }

    fun createOrUpdateMergedManga(
        title: String,
        coverUrl: String? = null,
        synopsis: String? = null,
        author: String? = null,
        artist: String? = null,
        status: String? = null,
        genres: String? = null,
        malId: Long? = null,
        preferredLanguage: String = "en",
    ): Long {
        val db = dbHelper.writableDatabase
        val now = System.currentTimeMillis()
        val cleanTitle = title.trim()

        var existingId: Long? = null
        if (malId != null && malId > 0) {
            val c = db.rawQuery(
                "SELECT id FROM merged_manga WHERE mal_id = ? LIMIT 1",
                arrayOf(malId.toString()),
            )
            if (c.moveToFirst()) existingId = c.getLong(0)
            c.close()
        }
        if (existingId == null) {
            val c = db.rawQuery(
                "SELECT id FROM merged_manga WHERE LOWER(title) = LOWER(?) LIMIT 1",
                arrayOf(cleanTitle),
            )
            if (c.moveToFirst()) existingId = c.getLong(0)
            c.close()
        }

        val values = ContentValues().apply {
            put("title", cleanTitle)
            put("cover_url", coverUrl)
            put("synopsis", synopsis)
            put("author", author)
            put("artist", artist)
            put("status", status)
            put("genres", genres)
            if (malId != null) put("mal_id", malId) else putNull("mal_id")
            put("preferred_language", preferredLanguage)
            put("updated_at", now)
        }

        val id = if (existingId != null) {
            db.update("merged_manga", values, "id = ?", arrayOf(existingId.toString()))
            existingId
        } else {
            values.put("created_at", now)
            db.insert("merged_manga", null, values)
        }

        refreshFlow()
        return id
    }

    fun createOrUpdateMergedManga(manga: MergedManga): Long {
        return createOrUpdateMergedManga(
            title = manga.title,
            coverUrl = manga.coverUrl,
            synopsis = manga.synopsis,
            author = manga.author,
            artist = manga.artist,
            status = manga.status,
            genres = manga.genres,
            malId = manga.malId,
            preferredLanguage = manga.preferredLanguage,
        )
    }

    fun clearReferences(mergedId: Long) {
        try {
            dbHelper.writableDatabase.delete(
                "merged_manga_reference",
                "merged_id = ?",
                arrayOf(mergedId.toString()),
            )
        } catch (_: Exception) {
        }
    }

    fun addReference(ref: MergedMangaReference) {
        try {
            val values = ContentValues().apply {
                put("merged_id", ref.mergedId)
                put("source_id", ref.sourceId)
                put("manga_url", ref.mangaUrl)
                put("manga_title", ref.mangaTitle)
                put("chapter_count", ref.chapterCount)
                put("is_info_source", if (ref.isInfoSource) 1 else 0)
                put("priority", ref.priority)
                put("source_name", ref.sourceName)
            }
            dbHelper.writableDatabase.insertWithOnConflict(
                "merged_manga_reference",
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        } catch (_: Exception) {
            try {
                val values = ContentValues().apply {
                    put("merged_id", ref.mergedId)
                    put("source_id", ref.sourceId)
                    put("manga_url", ref.mangaUrl)
                    put("manga_title", ref.mangaTitle)
                    put("chapter_count", ref.chapterCount)
                    put("is_info_source", if (ref.isInfoSource) 1 else 0)
                    put("priority", ref.priority)
                }
                dbHelper.writableDatabase.insertWithOnConflict(
                    "merged_manga_reference",
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            } catch (_: Exception) {
            }
        }
    }

    fun updateReferenceChapterCount(
        mergedId: Long,
        sourceId: Long,
        mangaUrl: String,
        count: Int,
    ) {
        try {
            val values = ContentValues().apply { put("chapter_count", count) }
            dbHelper.writableDatabase.update(
                "merged_manga_reference",
                values,
                "merged_id = ? AND source_id = ? AND manga_url = ?",
                arrayOf(mergedId.toString(), sourceId.toString(), mangaUrl),
            )
        } catch (_: Exception) {
        }
    }

    fun getMergedMangaById(id: Long): MergedManga? {
        try {
            val cursor = dbHelper.readableDatabase.rawQuery(
                "SELECT * FROM merged_manga WHERE id = ? LIMIT 1",
                arrayOf(id.toString()),
            )
            if (cursor.moveToFirst()) {
                val manga = readMergedManga(cursor)
                cursor.close()
                return manga
            }
            cursor.close()
        } catch (_: Exception) {
        }
        return null
    }

    fun getReferences(mergedId: Long): List<MergedMangaReference> {
        val list = mutableListOf<MergedMangaReference>()
        try {
            val cursor = dbHelper.readableDatabase.rawQuery(
                "SELECT * FROM merged_manga_reference WHERE merged_id = ? ORDER BY priority DESC",
                arrayOf(mergedId.toString()),
            )
            if (cursor.moveToFirst()) {
                do {
                    val nameIdx = cursor.getColumnIndex("source_name")
                    list.add(
                        MergedMangaReference(
                            id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                            mergedId = cursor.getLong(cursor.getColumnIndexOrThrow("merged_id")),
                            sourceId = cursor.getLong(cursor.getColumnIndexOrThrow("source_id")),
                            mangaUrl = cursor.getString(cursor.getColumnIndexOrThrow("manga_url")),
                            mangaTitle = cursor.getString(cursor.getColumnIndexOrThrow("manga_title")),
                            chapterCount = cursor.getInt(cursor.getColumnIndexOrThrow("chapter_count")),
                            isInfoSource = cursor.getInt(
                                cursor.getColumnIndexOrThrow("is_info_source"),
                            ) == 1,
                            priority = cursor.getInt(cursor.getColumnIndexOrThrow("priority")),
                            sourceName = if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
                                cursor.getString(nameIdx)
                            } else {
                                null
                            },
                        ),
                    )
                } while (cursor.moveToNext())
            }
            cursor.close()
        } catch (_: Exception) {
        }
        return list
    }

    fun addChapters(chapters: List<MergedChapter>) {
        if (chapters.isEmpty()) return
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            chapters.forEach { ch ->
                val values = ContentValues().apply {
                    put("merged_id", ch.mergedId)
                    put("source_id", ch.sourceId)
                    put("url", ch.url)
                    put("name", ch.name)
                    put("chapter_number", ch.chapterNumber)
                    put("language", ch.language)
                    put("date_upload", ch.dateUpload)
                }
                db.insertWithOnConflict(
                    "merged_chapter",
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getChapters(mergedId: Long): List<MergedChapter> {
        val list = mutableListOf<MergedChapter>()
        try {
            val cursor = dbHelper.readableDatabase.rawQuery(
                "SELECT * FROM merged_chapter WHERE merged_id = ? ORDER BY chapter_number ASC, date_upload ASC",
                arrayOf(mergedId.toString()),
            )
            if (cursor.moveToFirst()) {
                do {
                    list.add(
                        MergedChapter(
                            id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                            mergedId = cursor.getLong(cursor.getColumnIndexOrThrow("merged_id")),
                            sourceId = cursor.getLong(cursor.getColumnIndexOrThrow("source_id")),
                            url = cursor.getString(cursor.getColumnIndexOrThrow("url")),
                            name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                            chapterNumber = cursor.getFloat(
                                cursor.getColumnIndexOrThrow("chapter_number"),
                            ),
                            language = cursor.getString(cursor.getColumnIndexOrThrow("language")),
                            dateUpload = cursor.getLong(cursor.getColumnIndexOrThrow("date_upload")),
                        ),
                    )
                } while (cursor.moveToNext())
            }
            cursor.close()
        } catch (_: Exception) {
        }
        return list
    }

    fun subscribeToMergedManga(): StateFlow<List<MergedManga>> {
        return mergedFlow.asStateFlow()
    }

    private fun readMergedManga(cursor: android.database.Cursor): MergedManga {
        val malIdx = cursor.getColumnIndex("mal_id")
        return MergedManga(
            id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
            title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
            coverUrl = cursor.getString(cursor.getColumnIndexOrThrow("cover_url")),
            synopsis = cursor.getString(cursor.getColumnIndexOrThrow("synopsis")),
            author = cursor.getString(cursor.getColumnIndexOrThrow("author")),
            artist = cursor.getString(cursor.getColumnIndexOrThrow("artist")),
            status = cursor.getString(cursor.getColumnIndexOrThrow("status")),
            genres = cursor.getString(cursor.getColumnIndexOrThrow("genres")),
            malId = if (malIdx >= 0 && !cursor.isNull(malIdx)) cursor.getLong(malIdx) else null,
            preferredLanguage = cursor.getString(
                cursor.getColumnIndexOrThrow("preferred_language"),
            ) ?: "en",
            createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
            updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")),
        )
    }
}
