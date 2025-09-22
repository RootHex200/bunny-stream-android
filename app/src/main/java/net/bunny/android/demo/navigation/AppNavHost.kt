package net.bunny.android.demo.navigation

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import net.bunny.android.demo.App
import net.bunny.android.demo.home.HOME_ROUTE
import net.bunny.android.demo.home.TVHomeScreenRoute
import net.bunny.android.demo.home.homeScreen
import net.bunny.android.demo.library.libraryScreen
import net.bunny.android.demo.library.navigateToLibrary
import net.bunny.android.demo.player.navigateToPlayer
import net.bunny.android.demo.player.playerScreen
import net.bunny.android.demo.recording.RecordingActivity
import net.bunny.android.demo.resume.ResumePositionManagementRoute
import net.bunny.android.demo.settings.ResumePositionSettingsRoute
import net.bunny.android.demo.settings.navigateToSettings
import net.bunny.android.demo.settings.settingsScreen
import net.bunny.android.demo.ui.AppState

@Composable
fun AppNavHost(
    appState: AppState,
    onShowSnackbar: suspend (String, String?) -> Boolean,
    modifier: Modifier = Modifier,
    startDestination: String = HOME_ROUTE,
) {
    val navController = appState.navController
    val context = LocalContext.current
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
    ) {
        homeScreen(
            appState = appState,
            navigateToSettings = navController::navigateToSettings,
            navigateToVideoList = navController::navigateToLibrary,
            navigateToUpload = { navController.navigateToLibrary(showUpload = true) },
            navigateToPlayer = { videoId, libraryId, token, expires -> 
                navController.navigateToPlayer(videoId, libraryId, null, token, expires)
            },
            navigateToStreaming = {
                context.startActivity(Intent(context, RecordingActivity::class.java))
            },
            navigateToResumeSettings = navController::navigateToResumeSettings,
            navigateToResumeManagement = navController::navigateToResumeManagement,
            modifier = modifier
        )
        libraryScreen(
            appState = appState,
            navigateToSettings = navController::navigateToSettings,
            navigateToPlayer = { videoId -> navController.navigateToPlayer(videoId, null) },
        )
        settingsScreen(appState = appState)
        playerScreen(appState = appState)
        resumePositionSettingsScreen(appState = appState)
        resumePositionManagementScreen(
            appState = appState,
            onPlayVideo = navController::navigateToPlayer
        )
    }
}

fun NavGraphBuilder.tvHomeScreen(
    appState: AppState,
    navigateToSettings: () -> Unit,
    navigateToVideoList: () -> Unit,
    navigateToUpload: () -> Unit,
    navigateToStreaming: () -> Unit,
    navigateToTVPlayer: (String, Long, String?, Long?) -> Unit,
    navigateToResumeSettings: () -> Unit,
    navigateToResumeManagement: () -> Unit,
    modifier: Modifier = Modifier,
) {
    composable(
        route = HOME_ROUTE,
    ) {
        TVHomeScreenRoute(
            appState = appState,
            localPrefs = App.di.localPrefs,
            navigateToSettings = navigateToSettings,
            navigateToVideoList = navigateToVideoList,
            navigateToUpload = navigateToUpload,
            navigateToStreaming = navigateToStreaming,
            navigateToTVPlayer = navigateToTVPlayer,
            navigateToResumeSettings = navigateToResumeSettings,
            navigateToResumeManagement = navigateToResumeManagement,
            modifier = modifier
        )
    }
}

// Navigation extension functions
fun NavController.navigateToResumeSettings(navOptions: NavOptions? = null) {
    this.navigate("resume_settings", navOptions)
}

fun NavController.navigateToResumeManagement(navOptions: NavOptions? = null) {
    this.navigate("resume_management", navOptions)
}

// Navigation destination functions
fun NavGraphBuilder.resumePositionSettingsScreen(appState: AppState) {
    composable("resume_settings") {
        ResumePositionSettingsRoute(appState = appState)
    }
}

fun NavGraphBuilder.resumePositionManagementScreen(
    appState: AppState,
    onPlayVideo: (String, Long?) -> Unit
) {
    composable("resume_management") {
        ResumePositionManagementRoute(
            appState = appState,
            onPlayVideo = onPlayVideo
        )
    }
}