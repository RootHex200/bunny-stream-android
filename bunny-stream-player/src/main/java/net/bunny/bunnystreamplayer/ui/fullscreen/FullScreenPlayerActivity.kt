package net.bunny.bunnystreamplayer.ui.fullscreen

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.util.Log
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import net.bunny.bunnystreamplayer.DefaultBunnyPlayer
import net.bunny.bunnystreamplayer.model.PlayerIconSet
import net.bunny.bunnystreamplayer.ui.widget.BunnyPlayerView
import net.bunny.bunnystreamplayer.util.ScreenshotProtectionUtil
import net.bunny.player.R

/**
 * @suppress
 */
class FullScreenPlayerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "FullScreenPlayerActivity"
        private const val RESULT_RECEIVER = "RESULT_RECEIVER"
        private const val ICON_SET = "ICON_SET"
        private const val IS_PORTRAIT = "IS_PORTRAIT"
        private const val SCREENSHOT_PROTECTION_ENABLED = "SCREENSHOT_PROTECTION_ENABLED"

        fun show(context: Context, iconSet: PlayerIconSet, isPortrait: Boolean, screenshotProtectionEnabled: Boolean, onFullscreenExited: () -> Unit) {
            val resultReceiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    onFullscreenExited.invoke()
                }
            }

            val intent = Intent(context, FullScreenPlayerActivity::class.java)
            intent.putExtra(RESULT_RECEIVER, resultReceiver)
            intent.putExtra(ICON_SET, iconSet)
            intent.putExtra(IS_PORTRAIT, isPortrait)
            intent.putExtra(SCREENSHOT_PROTECTION_ENABLED, screenshotProtectionEnabled)
            context.startActivity(intent)
        }
    }

    private val iconSet by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(ICON_SET, PlayerIconSet::class.java)!!
        } else {
            intent.getParcelableExtra(ICON_SET)!!
        }
    }

    private val resultReceiver by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(RESULT_RECEIVER, ResultReceiver::class.java)!!
        } else {
            intent.getParcelableExtra(RESULT_RECEIVER)!!
        }
    }

    private val isPortrait by lazy {
        intent.getBooleanExtra(IS_PORTRAIT, false)
    }

    private val screenshotProtectionEnabled by lazy {
        intent.getBooleanExtra(SCREENSHOT_PROTECTION_ENABLED, false)
    }

    private val playerView by lazy { findViewById<BunnyPlayerView>(R.id.player_view) }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate, isPortrait=$isPortrait, screenshotProtectionEnabled=$screenshotProtectionEnabled")

        // Set orientation based on isPortrait value
        if (isPortrait) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        } else {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }

        setFullscreenMode()
        
        // Apply screenshot protection to the fullscreen activity
        ScreenshotProtectionUtil.setScreenshotProtection(this, screenshotProtectionEnabled)

        setContentView(R.layout.activity_fullscreen_player)
        playerView.bunnyPlayer = DefaultBunnyPlayer.getInstance(this)
        playerView.isFullscreen = true
        playerView.iconSet = iconSet
        
        // Apply screenshot protection to the player instance
        if (screenshotProtectionEnabled) {
            playerView.bunnyPlayer?.setScreenshotProtection(true)
        }
        playerView.fullscreenListener = object : BunnyPlayerView.FullscreenListener {
            override fun onFullscreenToggleClicked() {
                finish()
                resultReceiver.send(0, null)
            }
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    Log.d(TAG, "handleOnBackPressed")
                    finish()
                    resultReceiver.send(0, null)
                }
            }
        )
    }

    private fun setFullscreenMode(){
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)

        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onResume() {
        super.onResume()
        if(playerView.bunnyPlayer?.autoPaused == true) {
            playerView.bunnyPlayer?.play()
        }
    }

    override fun onPause() {
        super.onPause()
        val autoPaused = playerView.bunnyPlayer?.isPlaying() == true
        playerView.bunnyPlayer?.pause(autoPaused)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Disable screenshot protection when activity is destroyed
        if (screenshotProtectionEnabled) {
            ScreenshotProtectionUtil.setScreenshotProtection(this, false)
        }
        playerView.bunnyPlayer = null
    }
}