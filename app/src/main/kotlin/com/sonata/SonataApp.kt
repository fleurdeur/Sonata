package com.sonata

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.sonata.core.designsystem.Dimensions
import com.sonata.core.designsystem.LocalRadius
import com.sonata.core.designsystem.components.LocalHazeState
import dev.chrisbanes.haze.hazeSource
import com.sonata.core.domain.repository.Settings
import com.sonata.core.domain.repository.SettingsRepository
import com.sonata.core.media.connection.PlayerConnection
import com.sonata.feature.home.HomeScreen
import com.sonata.feature.ingest.ImportSheet
import com.sonata.feature.library.LibraryScreen
import com.sonata.feature.mix.MixScreen
import com.sonata.feature.settings.SettingsScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chrisbanes.haze.rememberHazeState
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private data class Tab(val route: String, val label: String, val icon: ImageVector, val iconSelected: ImageVector)

private val tabs = listOf(
    Tab("home", "Home", Icons.Outlined.Home, Icons.Filled.Home),
    Tab("library", "Library", Icons.Outlined.LibraryMusic, Icons.Filled.LibraryMusic),
    Tab("mix", "Mix", Icons.Outlined.Tune, Icons.Filled.Tune),
    Tab("settings", "Settings", Icons.Outlined.Settings, Icons.Filled.Settings),
)

@HiltViewModel
class AppViewModel @Inject constructor(
    val player: PlayerConnection,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val settings: StateFlow<Settings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    init {
        viewModelScope.launch { player.connect() }
    }

    fun finishOnboarding() = viewModelScope.launch {
        settingsRepository.update { it.copy(onboardingDone = true) }
    }
}

/**
 * App shell: a navigation host with a floating, translucent navigation bar, a
 * mini player docked above it and one import sheet. The chrome disappears on
 * the full-screen player and while the first-run intro is shown.
 */
@Composable
fun SonataApp(
    pendingShares: List<String>,
    onConsumedShare: () -> Unit,
    initialOnboardingDone: Boolean,
) {
    val appViewModel: AppViewModel = hiltViewModel()
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route ?: "home"
    var onboardingDone by remember { mutableStateOf(initialOnboardingDone) }
    val radius = LocalRadius.current
    val hazeState = rememberHazeState()

    var showImport by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val settings by appViewModel.settings.collectAsState()
    val playerState by appViewModel.player.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var miniPlayerHidden by remember { mutableStateOf(false) }

    LaunchedEffect(playerState.current?.id) {
        // A new track always brings its mini player back.
        miniPlayerHidden = false
    }

    LaunchedEffect(pendingShares) {
        if (pendingShares.isNotEmpty()) {
            showImport = true
            onConsumedShare()
        }
    }

    val chromeVisible = route != "nowPlaying" && onboardingDone

    androidx.compose.runtime.CompositionLocalProvider(LocalHazeState provides hazeState) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .hazeSource(hazeState),
            ) {
                AppNavHost(navController, onImport = { showImport = true })
            }

            if (chromeVisible) {
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = Dimensions.bottomBarInset)
                        .padding(top = Dimensions.bottomBarInset, bottom = 8.dp),
                ) {
                    if (!miniPlayerHidden) {
                        MiniPlayerBar(
                            player = appViewModel.player,
                            onOpenNowPlaying = {
                                navController.navigate("nowPlaying") { launchSingleTop = true }
                            },
                            swipeToDismiss = settings.miniPlayerSwipeDismiss,
                            onDismiss = {
                                miniPlayerHidden = true
                                scope.launch {
                                    appViewModel.player.fadeOutAndPause()
                                    val result = snackbarHostState.showSnackbar(
                                        message = "Paused " + (playerState.current?.title ?: "playback"),
                                        actionLabel = "Undo",
                                        withDismissAction = true,
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        appViewModel.player.resumeAfterStop()
                                        miniPlayerHidden = false
                                    }
                                }
                            },
                        )
                    }
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        shape = RoundedCornerShape(28.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
                        shadowElevation = 10.dp,
                    ) {
                        NavigationBar(
                            containerColor = androidx.compose.ui.graphics.Color.Transparent,
                            tonalElevation = 0.dp,
                            modifier = Modifier.height(Dimensions.bottomBarHeight),
                            windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
                        ) {
                            tabs.forEach { tab ->
                                val selected = route == tab.route
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = {
                                        navController.navigate(tab.route) {
                                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = {
                                        Icon(
                                            if (selected) tab.iconSelected else tab.icon,
                                            contentDescription = null,
                                        )
                                    },
                                    label = { Text(tab.label) },
                                    colors = NavigationBarItemDefaults.colors(
                                        indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                        selectedIconColor = MaterialTheme.colorScheme.primary,
                                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                    ),
                                )
                            }
                            NavigationBarItem(
                                selected = false,
                                onClick = { showImport = true },
                                icon = {
                                    Box(
                                        Modifier
                                            .size(34.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(MaterialTheme.colorScheme.primary),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            Icons.Filled.Add,
                                            contentDescription = "Import a sound",
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                },
                                label = { Text("Import") },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                ),
                            )
                        }
                    }
                }
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 156.dp, start = 16.dp, end = 16.dp),
            )

            if (!onboardingDone) {
                OnboardingPager(
                    onFinish = {
                        onboardingDone = true
                        appViewModel.finishOnboarding()
                    },
                )
            }
        }
    }

    if (showImport) {
        ModalBottomSheet(
            onDismissRequest = { showImport = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            ImportSheet(
                onClose = { showImport = false },
                onOpenSound = {
                    showImport = false
                    navController.navigate("library") { launchSingleTop = true }
                },
            )
        }
    }
}

@Composable
private fun AppNavHost(
    navController: androidx.navigation.NavHostController,
    onImport: () -> Unit,
) {
    NavHost(navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onOpenSettings = { navController.navigate("settings") },
                onOpenSearch = { navController.navigate("search") },
                onOpenLibrary = { navController.navigate("library") },
                onOpenMix = { navController.navigate("mix") },
                onImport = onImport,
            )
        }
        composable("library") {
            LibraryScreen(onOpenSearch = { navController.navigate("search") })
        }
        composable("mix") {
            MixScreen()
        }
        composable("settings") {
            SettingsScreen(onOpenEq = { navController.navigate("settings/eq") })
        }
        composable("search") {
            com.sonata.feature.library.SearchOverlay(onClose = { navController.popBackStack() })
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
            val presets by settingsViewModel.presets.collectAsState()
            Column(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(top = 8.dp),
            ) {
                androidx.compose.material3.IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.Filled.ExpandMore, contentDescription = "Back")
                }
                com.sonata.feature.settings.eq.EqScreen(
                    dsp = settingsViewModel.dsp,
                    presets = presets,
                    onApplyPreset = settingsViewModel::applyPreset,
                    onSavePreset = settingsViewModel::savePreset,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}