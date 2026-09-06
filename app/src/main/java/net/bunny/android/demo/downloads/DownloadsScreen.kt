@file:OptIn(ExperimentalMaterial3Api::class)

package net.bunny.android.demo.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.text.KeyboardOptions
import androidx.media3.common.util.UnstableApi
import net.bunny.android.demo.ui.AppState
import net.bunny.bunnystreamplayer.download.BunnyDownloadProgress
import net.bunny.bunnystreamplayer.download.BunnyDownloadState

@UnstableApi
@Composable
fun DownloadsRoute(
    appState: AppState,
    onPlayOffline: (String, Long?, String?, Long?, String?) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DownloadsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeMessage()
    }

    // The download DB is written by a foreground service, so what is on disk can
    // change while this screen is open without any event reaching us.
    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                title = { Text("Offline downloads") },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            item {
                StartDownloadCard(
                    state = state,
                    onVideoId = viewModel::onVideoId,
                    onLibraryId = viewModel::onLibraryId,
                    onToken = viewModel::onToken,
                    onExpires = viewModel::onExpires,
                    onReferer = viewModel::onReferer,
                    onCacheKey = viewModel::onCacheKey,
                    onWifiOnly = viewModel::setWifiOnly,
                    onStart = viewModel::start,
                )
            }

            val active = state.live.values.filter { it.state.isActive() }
            if (active.isNotEmpty()) {
                item {
                    SectionHeader("In progress")
                }
                items(active, key = { it.cacheKey }) { progress ->
                    ActiveDownloadRow(
                        progress = progress,
                        onCancel = { viewModel.cancel(progress.cacheKey) },
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionHeader("Downloaded (${state.completed.size})")
                    Row {
                        TextButton(onClick = viewModel::refresh) { Text("Refresh") }
                        if (state.completed.isNotEmpty()) {
                            TextButton(onClick = viewModel::deleteAll) { Text("Delete all") }
                        }
                    }
                }
            }

            if (state.completed.isEmpty()) {
                item {
                    Text(
                        text = "Nothing downloaded yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }

            items(state.completed, key = { it.cacheKey }) { row ->
                CompletedDownloadRow(
                    row = row,
                    onPlayOffline = {
                        onPlayOffline(
                            // Offline playback replays metadata stored under the
                            // cache key, so the key doubles as the video id when
                            // the form is empty.
                            state.videoId.trim().ifEmpty { row.cacheKey },
                            state.libraryId.trim().toLongOrNull(),
                            state.token.trim().ifEmpty { null },
                            state.expires.trim().toLongOrNull(),
                            row.cacheKey,
                        )
                    },
                    onDelete = { viewModel.delete(row.cacheKey) },
                )
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun StartDownloadCard(
    state: DownloadsUiState,
    onVideoId: (String) -> Unit,
    onLibraryId: (String) -> Unit,
    onToken: (String) -> Unit,
    onExpires: (String) -> Unit,
    onReferer: (String) -> Unit,
    onCacheKey: (String) -> Unit,
    onWifiOnly: (Boolean) -> Unit,
    onStart: () -> Unit,
) {
    Card(modifier = Modifier.padding(vertical = 12.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Start a download", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = state.videoId,
                onValueChange = onVideoId,
                label = { Text("Video ID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.libraryId,
                onValueChange = onLibraryId,
                label = { Text("Library ID") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.token,
                onValueChange = onToken,
                label = { Text("Token (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.expires,
                onValueChange = onExpires,
                label = { Text("Expires (optional)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.referer,
                onValueChange = onReferer,
                label = { Text("Referer (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.cacheKey,
                onValueChange = onCacheKey,
                label = { Text("Cache key (defaults to Video ID)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Wi-Fi only", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Off lets downloads run on mobile data",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = state.wifiOnly, onCheckedChange = onWifiOnly)
            }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onStart,
                enabled = !state.starting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.starting) "Starting…" else "Start download")
            }
        }
    }
}

@Composable
private fun ActiveDownloadRow(
    progress: BunnyDownloadProgress,
    onCancel: () -> Unit,
) {
    Card(modifier = Modifier.padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(progress.cacheKey, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(4.dp))
            Text(progress.describe(), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            // The manager reports -1 until it knows the total size.
            if (progress.progress >= 0f) {
                LinearProgressIndicator(
                    progress = { progress.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun CompletedDownloadRow(
    row: DownloadRow,
    onPlayOffline: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(row.cacheKey, style = MaterialTheme.typography.bodyLarge)
            Text(formatBytes(row.sizeBytes), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPlayOffline) { Text("Play offline") }
                OutlinedButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

private fun BunnyDownloadState.isActive(): Boolean =
    this == BunnyDownloadState.QUEUED || this == BunnyDownloadState.DOWNLOADING

private fun BunnyDownloadProgress.describe(): String = when (state) {
    BunnyDownloadState.QUEUED -> "Queued"
    BunnyDownloadState.DOWNLOADING ->
        if (progress >= 0f) {
            "Downloading ${(progress * 100).toInt()}% · ${formatBytes(bytesDownloaded)}"
        } else {
            "Downloading · ${formatBytes(bytesDownloaded)}"
        }
    BunnyDownloadState.DOWNLOADED -> "Downloaded · ${formatBytes(bytesDownloaded)}"
    BunnyDownloadState.FAILED -> "Failed: ${error.label()}"
    BunnyDownloadState.CANCELLED -> "Cancelled"
}
