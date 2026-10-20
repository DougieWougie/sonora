// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationItemIconPosition
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlin.reflect.KClass
import kotlinx.serialization.Serializable
import org.sonora.feature.discover.DiscoverScreen
import org.sonora.feature.downloads.DownloadsScreen
import org.sonora.feature.episodes.EpisodesScreen
import org.sonora.feature.library.LibraryScreen
import kotlinx.coroutines.flow.Flow
import org.sonora.feature.player.FullPlayer
import org.sonora.feature.player.PlayerEvent
import org.sonora.feature.player.PlayerIntent
import org.sonora.feature.player.canRetry
import org.sonora.feature.player.shareIntent
import org.sonora.feature.player.userMessage
import org.sonora.feature.player.MiniPlayer
import org.sonora.feature.player.PlayerViewModel
import org.sonora.feature.podcast.PodcastDetailScreen
import org.sonora.feature.settings.SettingsScreen
import org.sonora.ui.theme.ArtworkColorExtractor
import org.sonora.ui.theme.PodcastExpressiveTheme
import org.sonora.ui.theme.ThemeState
import org.sonora.ui.theme.ThemeViewModel

@Serializable data object LibraryRoute
@Serializable data object EpisodesRoute
@Serializable data object DiscoverRoute
@Serializable data object SettingsRoute
@Serializable data object DownloadsRoute

/** Argument name must match [org.sonora.feature.podcast.PodcastDetailViewModel.ARG_PODCAST_ID]. */
@Serializable data class PodcastRoute(val podcastId: Long)

private enum class TopLevel(val route: Any, val routeClass: KClass<*>, val label: String, val icon: ImageVector) {
    Library(LibraryRoute, LibraryRoute::class, "Library", Icons.Rounded.LibraryMusic),
    Episodes(EpisodesRoute, EpisodesRoute::class, "Episodes", Icons.Rounded.Inbox),
    Discover(DiscoverRoute, DiscoverRoute::class, "Discover", Icons.Rounded.Explore),
    Settings(SettingsRoute, SettingsRoute::class, "Settings", Icons.Rounded.Settings),
}

private val COMPACT_WIDTH = 600.dp
private val TALL_HEIGHT = 480.dp

internal enum class NavigationLayout { BottomBar, SlimBottomBar, Rail }

/**
 * A rail needs room both ways, so only tablets and foldables get one. A phone turned sideways is wide but
 * short: it keeps the bottom bar, slimmed down, so rotating does not move the navigation around.
 */
internal fun navigationLayoutFor(width: Dp, height: Dp): NavigationLayout = when {
    width < COMPACT_WIDTH -> NavigationLayout.BottomBar
    height < TALL_HEIGHT -> NavigationLayout.SlimBottomBar
    else -> NavigationLayout.Rail
}

@Composable
fun SonoraRoot(
    themeViewModel: ThemeViewModel = hiltViewModel(),
    playerViewModel: PlayerViewModel = hiltViewModel(),
) {
    val theme by themeViewModel.state.collectAsStateWithLifecycle()
    val player by playerViewModel.uiState.collectAsStateWithLifecycle()
    SonoraRootContent(
        theme, player, playerViewModel.events, playerViewModel::onIntent, themeViewModel::onArtworkSeed, HiltAppScreens,
    )
}

/** [SonoraRoot] without the view models, so tests can drive the theme and player state directly. */
@Composable
internal fun SonoraRootContent(
    theme: ThemeState,
    player: org.sonora.feature.player.PlayerUiState,
    playerEvents: Flow<PlayerEvent>,
    onPlayerIntent: (PlayerIntent) -> Unit,
    onArtworkSeed: (Int?) -> Unit,
    screens: AppScreens,
) {
    // Re-tint from the playing episode's artwork. Keep the last palette while the next cover loads.
    val seed = ArtworkColorExtractor.rememberSeed(player.episode?.artworkUrl)
    LaunchedEffect(seed, player.episode == null) {
        if (seed != null) onArtworkSeed(seed) else if (player.episode == null) onArtworkSeed(null)
    }

    PodcastExpressiveTheme(theme) {
        SonoraScaffold(player, playerEvents, onPlayerIntent) { navController, snackbar, modifier ->
            AppNavHost(navController, snackbar, modifier, screens)
        }
    }
}

@Composable
private fun currentNavigationLayout(): NavigationLayout = with(LocalDensity.current) {
    val size = LocalWindowInfo.current.containerSize
    navigationLayoutFor(size.width.toDp(), size.height.toDp())
}

/** The app shell. [navHost] is a slot so tests can put plain screens behind the real navigation and players. */
@Composable
internal fun SonoraScaffold(
    player: org.sonora.feature.player.PlayerUiState,
    playerEvents: Flow<PlayerEvent>,
    onPlayerIntent: (org.sonora.feature.player.PlayerIntent) -> Unit,
    layout: NavigationLayout = currentNavigationLayout(),
    navHost: @Composable (NavHostController, SnackbarHostState, Modifier) -> Unit,
) {
    val navController = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    // The full player covers the scaffold, so while it is open errors are shown above it instead.
    val fullPlayerSnackbar = remember { SnackbarHostState() }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val currentExpanded by rememberUpdatedState(expanded)
    val currentOnPlayerIntent by rememberUpdatedState(onPlayerIntent)
    LaunchedEffect(playerEvents) {
        playerEvents.collect { event ->
            if (event !is PlayerEvent.PlaybackFailed) return@collect
            val host = if (currentExpanded) fullPlayerSnackbar else snackbar
            val result = host.showSnackbar(
                message = event.failure.userMessage(),
                actionLabel = if (event.failure.canRetry) "Retry" else null,
                withDismissAction = true,
            )
            if (result == SnackbarResult.ActionPerformed) currentOnPlayerIntent(PlayerIntent.TogglePlayPause)
        }
    }
    val hasEpisode = player.episode != null

    LaunchedEffect(hasEpisode) { if (!hasEpisode) expanded = false }
    BackHandler(enabled = expanded) { expanded = false }

    SharedTransitionLayout {
        Box(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxSize()) {
                if (layout == NavigationLayout.Rail) TopLevelRail(navController)
                Scaffold(
                    modifier = Modifier.weight(1f),
                    snackbarHost = { SnackbarHost(snackbar) },
                    bottomBar = {
                        Column {
                            AnimatedVisibility(visible = hasEpisode && !expanded, enter = fadeIn(), exit = fadeOut()) {
                                MiniPlayer(player, this, onPlayerIntent, onExpand = { expanded = true })
                            }
                            when (layout) {
                                NavigationLayout.BottomBar -> TopLevelBar(navController)
                                NavigationLayout.SlimBottomBar -> SlimTopLevelBar(navController)
                                NavigationLayout.Rail -> Unit
                            }
                        }
                    },
                ) { padding ->
                    navHost(navController, snackbar, Modifier.padding(padding))
                }
            }
            AnimatedVisibility(visible = hasEpisode && expanded, enter = fadeIn(), exit = fadeOut()) {
                val context = LocalContext.current
                FullPlayer(player, this, onPlayerIntent, onCollapse = { expanded = false }, onShare = {
                    player.episode?.let { context.startActivity(Intent.createChooser(shareIntent(it), null)) }
                })
            }
            SnackbarHost(
                fullPlayerSnackbar,
                Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing),
            )
        }
    }
}

/** The screens behind each route: slots so tests can put plain content behind the real navigation graph. */
internal class AppScreens(
    val library: @Composable (onOpenPodcast: (Long) -> Unit, snackbar: SnackbarHostState) -> Unit,
    val episodes: @Composable (snackbar: SnackbarHostState, onOpenDownloads: () -> Unit) -> Unit,
    val discover: @Composable (snackbar: SnackbarHostState) -> Unit,
    val settings: @Composable (onOpenDownloads: () -> Unit) -> Unit,
    val downloads: @Composable (onBack: () -> Unit, snackbar: SnackbarHostState) -> Unit,
    val podcast: @Composable (onBack: () -> Unit, snackbar: SnackbarHostState) -> Unit,
)

internal val HiltAppScreens = AppScreens(
    library = { onOpenPodcast, snackbar -> LibraryScreen(onOpenPodcast = onOpenPodcast, snackbar = snackbar) },
    episodes = { snackbar, onOpenDownloads -> EpisodesScreen(snackbar, onOpenDownloads = onOpenDownloads) },
    discover = { snackbar -> DiscoverScreen(snackbar) },
    settings = { onOpenDownloads -> SettingsScreen(onOpenDownloads = onOpenDownloads) },
    downloads = { onBack, snackbar -> DownloadsScreen(onBack = onBack, snackbar = snackbar) },
    podcast = { onBack, snackbar -> PodcastDetailScreen(onBack = onBack, snackbar = snackbar) },
)

@Composable
internal fun AppNavHost(
    navController: NavHostController,
    snackbar: SnackbarHostState,
    modifier: Modifier,
    screens: AppScreens,
) {
    NavHost(navController, startDestination = LibraryRoute, modifier = modifier) {
        composable<LibraryRoute> {
            screens.library({ navController.navigate(PodcastRoute(it)) }, snackbar)
        }
        composable<EpisodesRoute> { screens.episodes(snackbar) { navController.navigate(DownloadsRoute) } }
        composable<DiscoverRoute> { screens.discover(snackbar) }
        composable<SettingsRoute> { screens.settings { navController.navigate(DownloadsRoute) } }
        composable<DownloadsRoute> { screens.downloads({ navController.popBackStack() }, snackbar) }
        composable<PodcastRoute> { screens.podcast({ navController.popBackStack() }, snackbar) }
    }
}

@Composable
private fun TopLevelBar(navController: NavHostController) {
    val destination = navController.currentBackStackEntryAsState().value?.destination
    NavigationBar {
        TopLevel.entries.forEach { top ->
            NavigationBarItem(
                selected = destination?.hierarchy?.any { it.hasRoute(top.routeClass) } == true,
                onClick = { navController.navigateTop(top.route) },
                icon = { androidx.compose.material3.Icon(top.icon, contentDescription = null) },
                label = { Text(top.label) },
            )
        }
    }
}

/** The landscape phone bar: icon beside label on a shorter bar, to leave the content room. */
@Composable
private fun SlimTopLevelBar(navController: NavHostController) {
    val destination = navController.currentBackStackEntryAsState().value?.destination
    ShortNavigationBar {
        TopLevel.entries.forEach { top ->
            ShortNavigationBarItem(
                selected = destination?.hierarchy?.any { it.hasRoute(top.routeClass) } == true,
                onClick = { navController.navigateTop(top.route) },
                icon = { androidx.compose.material3.Icon(top.icon, contentDescription = null) },
                label = { Text(top.label) },
                iconPosition = NavigationItemIconPosition.Start,
            )
        }
    }
}

@Composable
private fun TopLevelRail(navController: NavHostController) {
    val destination = navController.currentBackStackEntryAsState().value?.destination
    NavigationRail {
        TopLevel.entries.forEach { top ->
            NavigationRailItem(
                selected = destination?.hierarchy?.any { it.hasRoute(top.routeClass) } == true,
                onClick = { navController.navigateTop(top.route) },
                icon = { androidx.compose.material3.Icon(top.icon, contentDescription = null) },
                label = { Text(top.label) },
            )
        }
    }
}

private fun NavHostController.navigateTop(route: Any) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
