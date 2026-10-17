// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.ui

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
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
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
import org.sonora.feature.episodes.EpisodesScreen
import org.sonora.feature.library.LibraryScreen
import kotlinx.coroutines.flow.Flow
import org.sonora.feature.player.FullPlayer
import org.sonora.feature.player.PlayerEvent
import org.sonora.feature.player.PlayerIntent
import org.sonora.feature.player.canRetry
import org.sonora.feature.player.userMessage
import org.sonora.feature.player.MiniPlayer
import org.sonora.feature.player.PlayerViewModel
import org.sonora.feature.podcast.PodcastDetailScreen
import org.sonora.feature.settings.SettingsScreen
import org.sonora.ui.theme.ArtworkColorExtractor
import org.sonora.ui.theme.PodcastExpressiveTheme
import org.sonora.ui.theme.ThemeViewModel

@Serializable data object LibraryRoute
@Serializable data object EpisodesRoute
@Serializable data object DiscoverRoute
@Serializable data object SettingsRoute

/** Argument name must match [org.sonora.feature.podcast.PodcastDetailViewModel.ARG_PODCAST_ID]. */
@Serializable data class PodcastRoute(val podcastId: Long)

private enum class TopLevel(val route: Any, val routeClass: KClass<*>, val label: String, val icon: ImageVector) {
    Library(LibraryRoute, LibraryRoute::class, "Library", Icons.Rounded.LibraryMusic),
    Episodes(EpisodesRoute, EpisodesRoute::class, "Episodes", Icons.Rounded.Inbox),
    Discover(DiscoverRoute, DiscoverRoute::class, "Discover", Icons.Rounded.Explore),
    Settings(SettingsRoute, SettingsRoute::class, "Settings", Icons.Rounded.Settings),
}

private val COMPACT_WIDTH = 600.dp

@Composable
fun SonoraRoot(
    themeViewModel: ThemeViewModel = hiltViewModel(),
    playerViewModel: PlayerViewModel = hiltViewModel(),
) {
    val theme by themeViewModel.state.collectAsStateWithLifecycle()
    val player by playerViewModel.uiState.collectAsStateWithLifecycle()

    // Re-tint from the playing episode's artwork. Keep the last palette while the next cover loads.
    val seed = ArtworkColorExtractor.rememberSeed(player.episode?.artworkUrl)
    LaunchedEffect(seed, player.episode == null) {
        if (seed != null) themeViewModel.onArtworkSeed(seed) else if (player.episode == null) themeViewModel.onArtworkSeed(null)
    }

    PodcastExpressiveTheme(theme) {
        SonoraScaffold(player, playerViewModel.events, playerViewModel::onIntent)
    }
}

@Composable
private fun SonoraScaffold(
    player: org.sonora.feature.player.PlayerUiState,
    playerEvents: Flow<PlayerEvent>,
    onPlayerIntent: (org.sonora.feature.player.PlayerIntent) -> Unit,
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
    val widthDp = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    val compact = widthDp < COMPACT_WIDTH
    val hasEpisode = player.episode != null

    LaunchedEffect(hasEpisode) { if (!hasEpisode) expanded = false }
    BackHandler(enabled = expanded) { expanded = false }

    SharedTransitionLayout {
        Box(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxSize()) {
                if (!compact) TopLevelRail(navController)
                Scaffold(
                    modifier = Modifier.weight(1f),
                    snackbarHost = { SnackbarHost(snackbar) },
                    bottomBar = {
                        Column {
                            AnimatedVisibility(visible = hasEpisode && !expanded, enter = fadeIn(), exit = fadeOut()) {
                                MiniPlayer(player, this, onPlayerIntent, onExpand = { expanded = true })
                            }
                            if (compact) TopLevelBar(navController)
                        }
                    },
                ) { padding ->
                    AppNavHost(navController, snackbar, Modifier.padding(padding))
                }
            }
            AnimatedVisibility(visible = hasEpisode && expanded, enter = fadeIn(), exit = fadeOut()) {
                FullPlayer(player, this, onPlayerIntent, onCollapse = { expanded = false })
            }
            SnackbarHost(
                fullPlayerSnackbar,
                Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing),
            )
        }
    }
}

@Composable
private fun AppNavHost(navController: NavHostController, snackbar: SnackbarHostState, modifier: Modifier) {
    NavHost(navController, startDestination = LibraryRoute, modifier = modifier) {
        composable<LibraryRoute> {
            LibraryScreen(onOpenPodcast = { navController.navigate(PodcastRoute(it)) }, snackbar = snackbar)
        }
        composable<EpisodesRoute> { EpisodesScreen(snackbar) }
        composable<DiscoverRoute> { DiscoverScreen(snackbar) }
        composable<SettingsRoute> { SettingsScreen() }
        composable<PodcastRoute> { PodcastDetailScreen(onBack = { navController.popBackStack() }, snackbar = snackbar) }
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
