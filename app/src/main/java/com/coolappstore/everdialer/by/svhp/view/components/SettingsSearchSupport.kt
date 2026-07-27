package com.coolappstore.everdialer.by.svhp.view.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.awaitFrame
import kotlinx.coroutines.delay

/**
 * Applied to a settings row so that tapping a search result can reveal *where* that setting
 * lives (scrolling it into view and flashing it) instead of silently firing its action.
 *
 * Shared across SettingsScreen and every settings sub-screen so a search result that points
 * into a nested screen (e.g. "Auto Redial" inside Call Settings) can be scrolled to and
 * highlighted there too, after navigating with a `highlightKey` nav arg.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.settingsSearchHighlight(
    key: String,
    highlightedKey: String?,
    onConsumed: () -> Unit
): Modifier {
    val requester = remember(key) { BringIntoViewRequester() }
    val isHighlighted = highlightedKey == key
    val flash = remember(key) { Animatable(0f) }
    val highlightColor = MaterialTheme.colorScheme.primary
    LaunchedEffect(isHighlighted) {
        if (isHighlighted) {
            // The target row may not have completed its first layout pass yet — this fires
            // right when we switch from search results back to the full list, or right as a
            // freshly-navigated screen composes. Wait a few frames and retry so bringIntoView()
            // always has valid layout coordinates to scroll to.
            var attempt = 0
            var succeeded = false
            while (!succeeded && attempt < 6) {
                try {
                    requester.bringIntoView()
                    succeeded = true
                } catch (_: Exception) {
                    // Not laid out yet — wait and retry.
                    if (attempt == 0) delay(120) else awaitFrame()
                }
                attempt++
            }
            flash.snapTo(1f)
            flash.animateTo(0f, animationSpec = tween(1200))
            onConsumed()
        }
    }
    return this
        .bringIntoViewRequester(requester)
        .drawWithContent {
            drawContent()
            if (flash.value > 0f) {
                drawRoundRect(
                    color = highlightColor.copy(alpha = 0.30f * flash.value),
                    cornerRadius = CornerRadius(16.dp.toPx())
                )
            }
        }
}
