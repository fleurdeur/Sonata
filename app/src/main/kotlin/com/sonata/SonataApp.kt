package com.sonata

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonata.core.designsystem.Dimensions
import com.sonata.core.designsystem.LocalRadius
import com.sonata.core.designsystem.LocalSpacing
import com.sonata.core.designsystem.components.glassSurface
import com.sonata.core.media.connection.PlayerConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch
import com.sonata.core.domain.repository.Settings
import com.sonata.feature.home.HomeScreen
import com.sonata.feature.ingest.ImportSheet
import com.sonata.feature.library.LibraryScreen
import com.sonata.feature.mix.MixScreen
import com.sonata.feature.settings.SettingsScreen

private data class Tab(val route: String, val label: String, val icon: ImageVector, val iconSelected: ImageVector)

private val tabs = listOf(
    Tab("home", "Home", Icons.Outlined.Home, Icons.Filled.Home),
    Tab("library", "Library", Icons.Outlined.GraphicEq, Icons.Filled.GraphicEq),
    Tab("mix", "Mix", Icons.Outlined.Tune, Icons.Filled.Tune),
    Tab("settings", "Settings", Icons.Outlined.Settings, Icons.Filled.Settings),
)

/**
 * Global shell: single activity + Navigation Compose. Floating glass pill
 * bottom bar (64 dp, 16 dp side/bottom inset), mini-player 8 dp above it and
 * a floating 56 dp Import button on Home and Library only.
 */
@HiltViewModel
class AppViewModel @Inject constructor(
    val player: PlayerConnection,
) : ViewModel() {
    init {
        viewModelScope.launch { player.connect() }
    }
}

@Composable
fun SonataApp(
    pendingShares: List<String>,
    onConsumedShare: () -> Unit,
) {
    val appViewModel: AppViewModel = hiltViewModel()
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route ?: "home"
    var showImportSheet by remember { mutableStateOf(false) }
    val spacing = LocalSpacing.current

    Box(Modifier.fillMaxSize()) {
        // Feature destinations
        AppNavHost(navController)

        // Mini-player + floating bottom pill
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = Dimensions.bottomBarInset)
                .padding(bottom = Dimensions.bottomBarInset),
        ) {
            MiniPlayerBar(
                player = appViewModel.player,
                onOpenNowPlaying = { navController.navigate("nowPlaying") },
            )
            BottomPill(navController, route)
        }

        // Floating Import button on Home and Library only
        if (route == "home" || route == "library") {
            IconButton(
                onClick = { showImportSheet = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(y = -(Dimensions.bottomBarHeight + Dimensions.miniPlayerHeight + Dimensions.miniPlayerGap + 24.dp))
                    .padding(end = 4.dp)
                    .size(Dimensions.importButton)
                    .background(
                        MaterialTheme.colorScheme.primary,
                        RoundedCornerShape(50),
                    )
                    .semantics { contentDescription = "Import sound" },
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
            }
        }

        // Import modal sheet
        AnimatedVisibility(
            visible = showImportSheet,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(spacing.mdDp)
                    ,
            ) {
                ImportSheet(
                    onPickFiles = { showImportSheet = false },
                    onPickPhotos = { showImportSheet = false },
                    onRecord = { showImportSheet = false },
                    streamingEnabled = false,
                )
            }
        }

        // First-run onboarding
        var onboarding by remember { mutableStateOf(true) }
        LaunchedEffect(pendingShares) {
            if (pendingShares.isNotEmpty()) {
                showImportSheet = true
                onConsumedShare()
            }
        }
        if (onboarding) {
            OnboardingPager(onFinish = { onboarding = false })
        }
    }
}

@Composable
private fun BottomPill(navController: NavHostController, currentRoute: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(Dimensions.bottomBarHeight)
            .glassSurface(shape = LocalRadius.current.pillShape),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { tab ->
            val selected = currentRoute == tab.route
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clickable { navController.navigate(tab.route) { launchSingleTop = true } }
                    .semantics { contentDescription = tab.label },
            ) {
                if (selected) {
                    Box(
                        Modifier
                            .padding(top = 8.dp)
                            .size(width = 40.dp, height = 4.dp)
                            .background(
                                MaterialTheme.colorScheme.primary,
                                RoundedCornerShape(2.dp),
                            ),
                    )
                }
                Icon(
                    if (selected) tab.iconSelected else tab.icon,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    tab.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AppNavHost(navController: NavHostController) {
    NavHost(navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onOpenSettings = { navController.navigate("settings") },
                onOpenSearch = { navController.navigate("search") },
                onOpenLibrary = { navController.navigate("library") },
                onOpenMix = { navController.navigate("mix") },
            )
        }
        composable("library") {
            LibraryScreen(onOpenSearch = { navController.navigate("search") })
        }
        composable("mix") {
            MixScreen(onOpenSampler = { navController.navigate("mix/sampler") })
        }
        composable("settings") {
            SettingsScreen(
                onOpenEq = { navController.navigate("settings/eq") },
                onReplayTutorial = { },
            )
        }
        composable("search") {
            com.sonata.feature.library.SearchOverlay()
        }
        composable("nowPlaying") {
            val appViewModel: AppViewModel = hiltViewModel()
            NowPlayingScreen(
                player = appViewModel.player,
                onCollapse = { navController.popBackStack() },
                onOpenEq = { navController.navigate("settings/eq") },
            )
        }
        composable("settings/eq") {
            val settingsViewModel: com.sonata.feature.settings.SettingsViewModel = hiltViewModel()
            Column(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(top = 24.dp),
            ) {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(androidx.compose.material.icons.Icons.Default.ExpandMore, contentDescription = "Back")
                }
                com.sonata.feature.settings.eq.EqScreen(
                    dsp = settingsViewModel.dsp,
                    onOpenPresets = { },
                )
            }
        }
    }
}

private val Add = Icons.Filled.Add
