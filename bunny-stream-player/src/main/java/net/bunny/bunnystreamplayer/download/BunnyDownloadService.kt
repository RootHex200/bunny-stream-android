package net.bunny.bunnystreamplayer.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Scheduler

/**
 * Foreground service that keeps downloads running while the app is
 * backgrounded (R3) and lets the platform restart them after process death
 * (R4).
 *
 * The prototype's hand-rolled notification could not do either: a notification
 * posted from a coroutine does not make the work survive, it only makes it
 * visible. A foreground service is what modern Android actually requires for
 * long-running transfers.
 */
@UnstableApi
class BunnyDownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    NOTIFICATION_CHANNEL_ID,
    R_STRING_CHANNEL_NAME,
    0,
) {

    override fun getDownloadManager(): DownloadManager {
        ensureNotificationChannel(this)
        return BunnyDownloadManagerProvider.get(this)
    }

    /**
     * Lets the platform resume queued downloads once their requirements are
     * met again — after a reboot, or when Wi-Fi comes back.
     */
    override fun getScheduler(): Scheduler? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            PlatformScheduler(this, JOB_ID)
        } else {
            null
        }

    override fun getForegroundNotification(
        downloads: MutableList<Download>,
        notMetRequirements: Int,
    ): Notification {
        val inProgress = downloads.count { it.state == Download.STATE_DOWNLOADING }
        val queued = downloads.count { it.state == Download.STATE_QUEUED }

        val text = when {
            inProgress > 0 -> "Downloading $inProgress lesson${plural(inProgress)}"
            // Distinguishing "waiting for Wi-Fi" from a generic pause is the
            // difference between a student thinking it stalled and knowing why.
            queued > 0 && notMetRequirements != 0 -> "Waiting for Wi-Fi"
            queued > 0 -> "Download queued"
            else -> "Preparing download"
        }

        return androidx.core.app.NotificationCompat.Builder(
            this,
            NOTIFICATION_CHANNEL_ID,
        )
            .setContentTitle("Offline lessons")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .apply {
                val determinate = downloads.firstOrNull {
                    it.state == Download.STATE_DOWNLOADING &&
                        it.percentDownloaded >= 0
                }
                if (determinate != null) {
                    setProgress(100, determinate.percentDownloaded.toInt(), false)
                } else if (inProgress > 0) {
                    setProgress(0, 0, true)
                }
            }
            .build()
    }

    private fun plural(count: Int) = if (count == 1) "" else "s"

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "bunny_download_channel"
        private const val FOREGROUND_NOTIFICATION_ID = 4021
        private const val JOB_ID = 4022

        /** 0 means "no description resource", which the base class allows. */
        private const val R_STRING_CHANNEL_NAME = 0

        fun ensureNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

            val manager = context.getSystemService(NotificationManager::class.java)
                ?: return
            if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) != null) return

            manager.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "Lesson downloads",
                    // Low: a progress bar should not buzz a student's phone.
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Shows progress while lessons download for offline viewing"
                    setShowBadge(false)
                },
            )
        }
    }
}
