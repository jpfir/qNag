package com.exogroup.qnag.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Reusable transient swipe gesture wrapper.
 *
 * Swipe right = Recheck (blue), swipe left = ACK (green).
 * Snaps back after firing the command. Uses [swipeAllowed] as a scroll-safety
 * guard (the caller disables it while the list is scrolling).
 *
 * [backgroundPaddingVertical] / [backgroundShape] let the card style render a
 * rounded, vertically-padded action background while classic rows use the
 * default flat full-width background.
 */
@Composable
fun CommandSwipeContainer(
    problemKey: String,
    isSelectionMode: Boolean,
    swipeAllowed: Boolean,
    onAck: () -> Unit,
    onRecheck: () -> Unit,
    backgroundPaddingVertical: Dp = 0.dp,
    backgroundShape: Shape = RectangleShape,
    swipeIconHorizontalPadding: Dp = 16.dp,
    content: @Composable () -> Unit,
) {
    var commandLocked by remember { mutableStateOf(false) }
    var gestureAllowed by remember { mutableStateOf(false) }
    var isDragging by remember { mutableStateOf(false) }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    val offsetAnim = remember { Animatable(0f) }

    val visualOffset = if (isDragging) dragOffsetPx else offsetAnim.value

    // Reset stale swipe state when the item identity or selection mode changes.
    LaunchedEffect(problemKey, isSelectionMode) {
        if (!isDragging) {
            dragOffsetPx = 0f
            gestureAllowed = false
            commandLocked = false
            offsetAnim.snapTo(0f)
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val thresholdPx = widthPx * 0.45f
        val maxSwipePx = widthPx * 0.55f

        val draggableState = rememberDraggableState { delta ->
            if (!gestureAllowed) return@rememberDraggableState
            dragOffsetPx = (dragOffsetPx + delta).coerceIn(-maxSwipePx, maxSwipePx)
        }

        val progress = (abs(visualOffset) / thresholdPx).coerceIn(0f, 1f)
        val backgroundColor = when {
            visualOffset > 0f -> Color(0xFF1976D2).copy(alpha = 0.25f + progress * 0.75f)
            visualOffset < 0f -> Color(0xFF388E3C).copy(alpha = 0.25f + progress * 0.75f)
            else -> Color.Transparent
        }

        Box(modifier = Modifier.fillMaxWidth()) {
            // Action colour background — stays in place while content slides over it
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(vertical = backgroundPaddingVertical)
                    .background(backgroundColor, shape = backgroundShape),
                contentAlignment = if (visualOffset > 0f) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                when {
                    visualOffset > 0f -> Icon(
                        Icons.Default.Refresh, contentDescription = "Recheck",
                        tint = Color.White,
                        modifier = Modifier.padding(start = swipeIconHorizontalPadding),
                    )
                    visualOffset < 0f -> Icon(
                        Icons.Default.Check, contentDescription = "Acknowledge",
                        tint = Color.White,
                        modifier = Modifier.padding(end = swipeIconHorizontalPadding),
                    )
                }
            }

            // Sliding content wrapper
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset { IntOffset(visualOffset.roundToInt(), 0) }
                    .draggable(
                        state = draggableState,
                        orientation = Orientation.Horizontal,
                        enabled = !isSelectionMode,
                        onDragStarted = {
                            gestureAllowed = swipeAllowed && !isSelectionMode && !commandLocked
                            isDragging = gestureAllowed
                        },
                        onDragStopped = {
                            val finalOffset = dragOffsetPx
                            val action = when {
                                gestureAllowed && !commandLocked && abs(finalOffset) >= thresholdPx && finalOffset > 0f -> "recheck"
                                gestureAllowed && !commandLocked && abs(finalOffset) >= thresholdPx && finalOffset < 0f -> "ack"
                                else -> null
                            }
                            val shouldLock = action != null
                            if (shouldLock) commandLocked = true

                            try {
                                // Sync animation to drag position before clearing isDragging — no visual jump.
                                offsetAnim.snapTo(finalOffset)
                                isDragging = false
                                dragOffsetPx = 0f
                                gestureAllowed = false

                                // Fire command immediately and safely; failures surface via Command Activity.
                                if (action == "recheck") runCatching { onRecheck() }
                                else if (action == "ack") runCatching { onAck() }

                                offsetAnim.animateTo(0f, animationSpec = tween(durationMillis = 180))

                                if (shouldLock) {
                                    delay(120L)
                                    commandLocked = false
                                }
                            } finally {
                                // Runs on cancellation (e.g. item leaves composition mid-animation).
                                isDragging = false
                                dragOffsetPx = 0f
                                gestureAllowed = false
                                if (shouldLock) commandLocked = false
                                withContext(NonCancellable) { offsetAnim.snapTo(0f) }
                            }
                        },
                    )
            ) {
                content()
            }
        }
    }
}
