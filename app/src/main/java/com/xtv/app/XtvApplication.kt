package com.xtv.app

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.request.crossfade
import coil3.util.DebugLogger

/**
 * Application entry point.
 *
 * Coil is configured here because the ambient reel keeps a small decoded lookahead: the per-app heap
 * ceiling on the target hardware is 384 MB (`dalvik.vm.heapgrowthlimit`), and a 1080p ARGB_8888 bitmap
 * is ~8 MB, so a 3-deep lookahead is ~25 MB — comfortable, but only as long as decodes are capped to
 * the display size. X serves photos at 2048 px natively; decoding those at full size on a 4K-capable
 * box would be ~33 MB each and would blow the budget.
 *
 * Crossfade is off by default: the reel uses hard cuts (see ViewerScreen) because a dissolve cannot
 * work across the video SurfaceView layer anyway, and a half-applied fade looks worse than a cut.
 */
class XtvApplication : Application(), SingletonImageLoader.Factory {
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
            .crossfade(false)
            .logger(DebugLogger())
            .build()
}
