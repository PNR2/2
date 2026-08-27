package eu.kanade.tachiyomi.ui.discovery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import cafe.adriel.voyager.navigator.tab.Tab
import cafe.adriel.voyager.navigator.tab.TabOptions

/**
 * The main UI screen for the RSS News feed.
 */
object NewsTab : Tab {

    override val options: TabOptions
        @Composable
        get() {
            val title = "News"
            val icon = rememberVectorPainter(Icons.Outlined.Article)

            return remember {
                TabOptions(
                    index = 10u, // Assigning a high index so it loads at the end of the bottom bar
                    title = title,
                    icon = icon,
                )
            }
        }

    @Composable
    override fun Content() {
        // A simple layout that centers our temporary text on the screen
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "MUSYomi RSS News Feed (Coming Soon)")
        }
    }
}
