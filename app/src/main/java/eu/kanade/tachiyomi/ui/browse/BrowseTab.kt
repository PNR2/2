@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.ui.browse

import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.TabbedScreen
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.browse.cohesive.cohesiveTab
import eu.kanade.tachiyomi.ui.browse.extension.ExtensionsTab
import eu.kanade.tachiyomi.ui.browse.migration.sources.MigrateSourceTab
import eu.kanade.tachiyomi.ui.browse.source.SourcesTab
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import eu.kanade.tachiyomi.ui.main.MainActivity
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

data object BrowseTab : Tab {

    override val options: TabOptions
        @Composable
        get() {
            val isSelected = LocalNavigator.currentOrThrow.parent?.lastItem is BrowseTab
            val image = AnimatedImageVector.animatedVectorResource(R.drawable.anim_browse_enter)
            return TabOptions(
                index = 3u,
                title = stringResource(MR.strings.browse),
                icon = rememberAnimatedVectorPainter(image, isSelected),
            )
        }

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val navigator = LocalNavigator.currentOrThrow

        // Order: Sources | Extensions | Migrate | Cohesive
        val tabs = remember {
            persistentListOf(
                SourcesTab,
                ExtensionsTab(),
                MigrateSourceTab,
                cohesiveTab,
            )
        }

        val state = rememberPagerState { tabs.size }

        TabbedScreen(
            titleRes = MR.strings.browse,
            tabs = tabs,
            state = state,
            scrollable = true,
            actions = {
                AppBar.Action(
                    title = stringResource(MR.strings.action_global_search),
                    icon = R.drawable.ic_search_24dp,
                    onClick = {
                        navigator.push(GlobalSearchScreen())
                    },
                )
            },
        )

        LaunchedEffect(Unit) {
            (context as? MainActivity)?.ready = true
        }

        // Keep pager in sync when TabbedScreen changes page (if your TabbedScreen
        // does not own the pager itself, use HorizontalPager instead — see alt below)
        LaunchedEffect(state.currentPage) {
            // no-op; state is driven by TabbedScreen
        }
    }
}
