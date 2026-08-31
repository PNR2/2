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

        fun refreshFlow() {
            try {
                val db = dbHelper.readableDatabase
                val cursor = db.rawQuery(
                    "SELECT * FROM merged_manga ORDER BY updated_at DESC",
                    null,
                )
                val list = mutableListOf<MergedManga>()

                if (cursor.moveToFirst()) {
                    do {
                        val malIdIndex = cursor.getColumnIndex("mal_id")
                        list.add(
                            MergedManga(
                                id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                                title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
                                coverUrl = cursor.getString(cursor.getColumnIndexOrThrow("cover_url")),
                                synopsis = cursor.getString(cursor.getColumnIndexOrThrow("synopsis")),
                                author = cursor.getString(cursor.getColumnIndexOrThrow("author")),
                                artist = cursor.getString(cursor.getColumnIndexOrThrow("artist")),
                                status = cursor.getString(cursor.getColumnIndexOrThrow("status")),
                                genres = cursor.getString(cursor.getColumnIndexOrThrow("genres")),
                                malId = if (malIdIndex >= 0 && !cursor.isNull(malIdIndex)) {
                                    cursor.getLong(malIdIndex)
                                } else {
                                    null
                                },
                                preferredLanguage = cursor.getString(
                                    cursor.getColumnIndexOrThrow("preferred_language"),
                                ) ?: "en",
                                createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                                updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")),
                            ),
                        )
                    } while (cursor.moveToNext())
                }
                cursor.close()
                mergedFlow.value = list
            } catch (_: Exception) {
                mergedFlow.value = emptyList()
            }
        }
    }

    fun createMergedManga(manga: MergedManga): Long {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("title", manga.title)
            put("cover_url", manga.coverUrl)
            put("synopsis", manga.synopsis)
            put("author", manga.author)
            put("artist", manga.artist)
            put("status", manga.status)
            put("genres", manga.genres)
            put("mal_id", manga.malId)
            put("preferred_language", manga.preferredLanguage)
            put("created_at", manga.createdAt)
            put("updated_at", manga.updatedAt)
        }
        val id = db.insert("merged_manga", null, values)
        refreshFlow()
        return id
    }

    fun addReference(ref: MergedMangaReference) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("merged_id", ref.mergedId)
            put("source_id", ref.sourceId)
            put("manga_url", ref.mangaUrl)
            put("manga_title", ref.mangaTitle)
            put("chapter_count", ref.chapterCount)
            put("is_info_source", if (ref.isInfoSource) 1 else 0)
            put("priority", ref.priority)
        }
        db.insertWithOnConflict(
            "merged_manga_reference",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun updateReferenceChapterCount(mergedId: Long, sourceId: Long, mangaUrl: String, count: Int) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("chapter_count", count)
        }
        db.update(
            "merged_manga_reference",
            values,
            "merged_id = ? AND source_id = ? AND manga_url = ?",
            arrayOf(mergedId.toString(), sourceId.toString(), mangaUrl),
        )
    }

    fun addChapters(chapters: List<MergedChapter>) {
        if (chapters.isEmpty()) return
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            chapters.forEach { chapter ->
                val values = ContentValues().apply {
                    put("merged_id", chapter.mergedId)
                    put("source_id", chapter.sourceId)
                    put("url", chapter.url)
                    put("name", chapter.name)
                    put("chapter_number", chapter.chapterNumber)
                    put("language", chapter.language)
                    put("date_upload", chapter.dateUpload)
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

    fun getReferences(mergedId: Long): List<MergedMangaReference> {
        val list = mutableListOf<MergedMangaReference>()
        try {
            val db = dbHelper.readableDatabase
            val cursor = db.rawQuery(
                "SELECT * FROM merged_manga_reference WHERE merged_id = ? ORDER BY priority DESC, chapter_count DESC",
                arrayOf(mergedId.toString()),
            )

            if (cursor.moveToFirst()) {
                do {
                    list.add(
                        MergedMangaReference(
                            id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                            mergedId = cursor.getLong(cursor.getColumnIndexOrThrow("merged_id")),
                            sourceId = cursor.getLong(cursor.getColumnIndexOrThrow("source_id")),
                            mangaUrl = cursor.getString(cursor.getColumnIndexOrThrow("manga_url")),
                            mangaTitle = cursor.getString(cursor.getColumnIndexOrThrow("manga_title")),
                            chapterCount = cursor.getInt(cursor.getColumnIndexOrThrow("chapter_count")),
                            isInfoSource = cursor.getInt(cursor.getColumnIndexOrThrow("is_info_source")) == 1,
                            priority = cursor.getInt(cursor.getColumnIndexOrThrow("priority")),
                        ),
                    )
                } while (cursor.moveToNext())
            }
            cursor.close()
        } catch (_: Exception) {
        }
        return list
    }

    fun getChapters(mergedId: Long): List<MergedChapter> {
        val list = mutableListOf<MergedChapter>()
        try {
            val db = dbHelper.readableDatabase
            val cursor = db.rawQuery(
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
                            chapterNumber = cursor.getFloat(cursor.getColumnIndexOrThrow("chapter_number")),
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
}
