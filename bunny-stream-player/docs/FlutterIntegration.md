# Flutter Integration Guide

This guide explains how to optimize the BunnyStreamPlayer for Flutter PlatformLinkView integration to prevent flickering during fullscreen transitions.

## Problem

When embedding the BunnyStreamPlayer in Flutter using PlatformLinkView, users may experience flickering when transitioning between landscape fullscreen and portrait mode. This is caused by:

1. **Player Instance Recreation**: The player instance being set to null and recreated
2. **Surface Recreation**: The video surface being recreated during transitions
3. **Activity Lifecycle**: The fullscreen activity being destroyed and recreated
4. **No Smooth Transition**: Lack of smooth transitions between states

## Solution

The player now includes automatic Flutter detection and optimizations, plus manual optimization methods for better control.

## Automatic Optimizations

The player automatically detects if it's embedded in Flutter and applies optimizations:

```kotlin
// Automatic detection and optimization
val player = BunnyStreamPlayer(context)
// Flutter optimizations are applied automatically if detected
```

## Manual Optimizations

For more control, you can manually enable Flutter optimizations:

```kotlin
val player = BunnyStreamPlayer(context)
player.enableFlutterOptimizations()
```

## What the Optimizations Do

### 1. Hardware Acceleration
- Sets `LAYER_TYPE_HARDWARE` for smoother rendering
- Optimizes GPU usage for video playback

### 2. Surface Handling
- Sets black shutter background to prevent white flashes
- Optimizes surface creation and destruction

### 3. Controller Optimization
- Enables controller with optimal timeout settings
- Configures auto-show/hide behavior for embedded views

### 4. Fullscreen Transition Improvements
- Prevents player instance recreation during transitions
- Maintains surface continuity between embedded and fullscreen modes
- Adds smooth fade animations

### 5. Activity Theme Optimization
- Uses custom theme with smooth transitions
- Optimizes window flags for better performance

## Usage in Flutter

### Basic Integration

```dart
// In your Flutter app
PlatformLinkView(
  viewType: 'bunny_stream_player',
  creationParams: {
    'videoId': 'your-video-id',
    'libraryId': 123,
    'isScreenshotProtectionEnabled': true,
  },
  creationParamsCodec: const StandardMessageCodec(),
)
```

### Android Native Code

```kotlin
// In your Android PlatformView implementation
class BunnyStreamPlayerView(context: Context) : PlatformView {
    private val player = BunnyStreamPlayer(context)
    
    init {
        // Enable Flutter optimizations
        player.enableFlutterOptimizations()
        
        // Set up the player
        player.playVideo(
            videoId = "your-video-id",
            libraryId = 123L,
            videoTitle = "Video Title",
            isScreenshotProtectionEnabled = true
        )
    }
    
    override fun getView(): View = player
}
```

## Best Practices

### 1. Enable Optimizations Early
Call `enableFlutterOptimizations()` as soon as possible after creating the player instance.

### 2. Handle Lifecycle Properly
```kotlin
class BunnyStreamPlayerView(context: Context) : PlatformView {
    private val player = BunnyStreamPlayer(context)
    
    init {
        player.enableFlutterOptimizations()
    }
    
    override fun dispose() {
        // Clean up resources
        player.release()
    }
}
```

### 3. Use Screenshot Protection
```kotlin
player.playVideo(
    videoId = "video-id",
    libraryId = 123L,
    videoTitle = "Title",
    isScreenshotProtectionEnabled = true // Enable protection
)
```

### 4. Handle Fullscreen Transitions
The optimizations automatically handle fullscreen transitions, but you can customize the behavior:

```kotlin
// The player automatically detects Flutter and applies optimizations
// No additional code needed for fullscreen transitions
```

## Troubleshooting

### Still Experiencing Flickering?

1. **Check Flutter Detection**: Ensure the player is properly embedded in Flutter
2. **Enable Manual Optimizations**: Call `enableFlutterOptimizations()` explicitly
3. **Check Hardware Acceleration**: Ensure your device supports hardware acceleration
4. **Update Flutter**: Use the latest Flutter version for best compatibility

### Performance Issues?

1. **Reduce Video Quality**: Lower the video resolution if needed
2. **Check Memory Usage**: Monitor memory consumption during playback
3. **Optimize Flutter App**: Ensure your Flutter app is optimized for performance

## API Reference

### BunnyStreamPlayer

```kotlin
class BunnyStreamPlayer {
    /**
     * Enable Flutter integration optimizations to prevent flickering
     * Call this method when embedding the player in Flutter PlatformLinkView
     */
    fun enableFlutterOptimizations()
    
    /**
     * Play video with screenshot protection
     */
    fun playVideo(
        videoId: String,
        libraryId: Long?,
        videoTitle: String,
        refererValue: String? = null,
        isPortrait: Boolean = false,
        isScreenshotProtectionEnabled: Boolean = false
    )
}
```

### FlutterIntegrationUtil

```kotlin
object FlutterIntegrationUtil {
    /**
     * Check if the view is embedded in Flutter
     */
    fun isEmbeddedInFlutter(view: View): Boolean
    
    /**
     * Apply Flutter-specific optimizations to prevent flickering
     */
    fun applyFlutterOptimizations(playerView: PlayerView, context: Context)
    
    /**
     * Handle smooth fullscreen transitions for Flutter
     */
    fun handleFlutterFullscreenTransition(
        playerView: PlayerView,
        bunnyPlayer: BunnyPlayer,
        isEnteringFullscreen: Boolean
    )
}
```

## Migration Guide

### From Previous Versions

If you're upgrading from a previous version:

1. **Remove Manual Surface Handling**: The optimizations now handle this automatically
2. **Update Fullscreen Code**: Remove any custom fullscreen transition code
3. **Enable Optimizations**: Add `enableFlutterOptimizations()` call
4. **Test Thoroughly**: Test fullscreen transitions in your Flutter app

### Example Migration

```kotlin
// Old code
val player = BunnyStreamPlayer(context)
// Manual surface handling code...

// New code
val player = BunnyStreamPlayer(context)
player.enableFlutterOptimizations()
// Automatic optimizations handle everything
```

## Support

For issues or questions about Flutter integration:

1. Check this documentation first
2. Review the example code in the demo app
3. Check the Flutter integration logs for debugging information
4. Contact support with specific error messages and device information
