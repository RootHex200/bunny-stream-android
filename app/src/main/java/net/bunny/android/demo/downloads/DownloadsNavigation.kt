package net.bunny.android.demo.downloads

import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.compose.composable
import net.bunny.android.demo.ui.AppState

const val DOWNLOADS_ROUTE = "downloads"

fun NavController.navigateToDownloads(navOptions: NavOptions? = null) {
    this.navigate(DOWNLOADS_ROUTE, navOptions)
}

@UnstableApi
fun NavGraphBuilder.downloadsScreen(
    appState: AppState,
    onPlayOffline: (String, Long?, String?, Long?, String?) -> Unit,
) {
    composable(DOWNLOADS_ROUTE) {
        DownloadsRoute(
            appState = appState,
            onPlayOffline = onPlayOffline,
        )
    }
}
