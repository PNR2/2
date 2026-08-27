package eu.kanade.tachiyomi.data.discovery

import kotlinx.coroutines.flow.Flow
import tachiyomi.data.DatabaseHandler
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Handles saving and retrieving RSS articles from the discovery.sq database.
 */
class RssNewsRepository {
    // Inject Mihon's core database handler
    private val handler: DatabaseHandler = Injekt.get()

    suspend fun insertNews(articles: List<RssNewsItem>) {
        handler.await {
            // Using a transaction speeds up SQLite massive inserts
            discoveryQueries.transaction {
                articles.forEach { article ->
                    discoveryQueries.insertNewsArticle(
                        title = article.title,
                        link = article.link,
                        description = article.description,
                        image_url = article.imageUrl,
                        publication_date = article.publicationDate,
                        source_name = article.sourceName,
                        is_read = false,
                    )
                }
            }
        }
    }

    fun subscribeToNews(): Flow<List<RssNewsItem>> {
        // subscribeToList automatically pushes updates to the UI whenever the table changes!
        return handler.subscribeToList {
            discoveryQueries.selectAllNews {
                    id,
                    title,
                    link,
                    description,
                    image_url,
                    publication_date,
                    source_name,
                    is_read,
                ->
                RssNewsItem(
                    title = title,
                    link = link,
                    description = description ?: "",
                    imageUrl = image_url,
                    publicationDate = publication_date,
                    sourceName = source_name,
                )
            }
        }
    }
}
