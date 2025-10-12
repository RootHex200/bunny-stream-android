package net.bunny.bunnystreamplayer.util

import android.content.Context
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.media3.ui.PlayerView
import net.bunny.bunnystreamplayer.common.BunnyPlayer

object FlutterIntegrationUtil {
    
    private const val TAG = "FlutterIntegrationUtil"
    
    /**
     * Optimize player view for Flutter PlatformLinkView integration
     * This helps prevent flickering during fullscreen transitions
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun optimizeForFlutterIntegration(playerView: PlayerView, context: Context) {
        try {
            // Set hardware acceleration for smoother rendering
            playerView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            
            // Optimize surface handling
            playerView.setShutterBackgroundColor(android.graphics.Color.BLACK)
            
            // Prevent unnecessary redraws
            playerView.setWillNotDraw(false)
            
            // Optimize for embedded view
            playerView.useController = true
            playerView.controllerAutoShow = true
            playerView.controllerHideOnTouch = true
            
            Log.d(TAG, "Player view optimized for Flutter integration")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to optimize player view for Flutter: ${e.message}")
        }
    }
    
    /**
     * Handle smooth fullscreen transitions for Flutter
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun handleFlutterFullscreenTransition(
        playerView: PlayerView,
        bunnyPlayer: BunnyPlayer,
        isEnteringFullscreen: Boolean
    ) {
        try {
            if (isEnteringFullscreen) {
                // Optimize for fullscreen entry
                playerView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                playerView.useController = true
                playerView.controllerAutoShow = true
            } else {
                // Optimize for fullscreen exit
                playerView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                
                // Ensure smooth transition back to embedded view
                playerView.post {
                    playerView.invalidate()
                }
            }
            
            Log.d(TAG, "Flutter fullscreen transition handled: entering=$isEnteringFullscreen")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to handle Flutter fullscreen transition: ${e.message}")
        }
    }
    
    /**
     * Check if the view is embedded in Flutter
     */
    fun isEmbeddedInFlutter(view: View): Boolean {
        return try {
            // Check if the view hierarchy contains Flutter-specific classes
            var parent = view.parent
            while (parent != null) {
                val className = parent.javaClass.name
                if (className.contains("flutter") || className.contains("Flutter")) {
                    return true
                }
                parent = if (parent is ViewGroup) parent.parent else null
            }
            false
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check Flutter embedding: ${e.message}")
            false
        }
    }
    
    /**
     * Apply Flutter-specific optimizations to prevent flickering
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun applyFlutterOptimizations(playerView: PlayerView, context: Context) {
        try {
            // Set optimal rendering mode
            playerView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            
            // Optimize surface handling for embedded views
            playerView.setShutterBackgroundColor(android.graphics.Color.BLACK)
            
            // Prevent unnecessary layout passes
            playerView.setWillNotDraw(false)
            
            // Optimize controller behavior for embedded views
            playerView.useController = true
            playerView.controllerAutoShow = true
            playerView.controllerHideOnTouch = true
            playerView.controllerShowTimeoutMs = 3000 // 3 seconds
            
            Log.d(TAG, "Flutter optimizations applied successfully")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply Flutter optimizations: ${e.message}")
        }
    }
}
