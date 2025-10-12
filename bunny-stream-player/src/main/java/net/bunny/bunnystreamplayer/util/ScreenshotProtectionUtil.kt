package net.bunny.bunnystreamplayer.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager

object ScreenshotProtectionUtil {
    
    /**
     * Enable screenshot and screen recording protection for the given context
     * @param context The context (Activity or View context)
     * @param enable true to enable protection, false to disable
     */
    fun setScreenshotProtection(context: Context, enable: Boolean) {
        val activity = context.findActivity() ?: return
        
        if (enable) {
            // Enable screenshot protection
            activity.window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        } else {
            // Disable screenshot protection
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
    
    /**
     * Check if screenshot protection is currently enabled
     * @param context The context to check
     * @return true if protection is enabled, false otherwise
     */
    fun isScreenshotProtectionEnabled(context: Context): Boolean {
        val activity = context.findActivity() ?: return false
        return (activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
    }
    
    /**
     * Find the Activity from a Context
     */
    private fun Context.findActivity(): Activity? {
        var context = this
        while (context is ContextWrapper) {
            if (context is Activity) {
                return context
            }
            context = context.baseContext
        }
        return null
    }
}
