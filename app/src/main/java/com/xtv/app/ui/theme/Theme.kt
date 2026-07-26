package com.xtv.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/**
 * App theme. TV apps are dark-first (rendered on large displays in dim rooms),
 * so we use a dark color scheme as the baseline.
 *
 * No `@OptIn` needed: androidx.tv:tv-material is stable as of 1.1.0. Only `Carousel` and the
 * `*Chip` family still carry `ExperimentalTvMaterial3Api`.
 */
@Composable
fun XtvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(),
        content = content,
    )
}
