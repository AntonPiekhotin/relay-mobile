package com.relay.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

const val SWIPE_BACK_TAG = "swipe-back"

private val EDGE_WIDTH = 24.dp
private const val COMMIT_FRACTION = 0.3f

@Composable
fun SwipeBackBox(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    val currentOnBack by rememberUpdatedState(onBack)

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag(SWIPE_BACK_TAG)
            .pointerInput(Unit) {
                val edge = EDGE_WIDTH.toPx()
                val commit = size.width * COMMIT_FRACTION
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (down.position.x > edge) return@awaitEachGesture
                    var travelled = 0f
                    val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, overSlop ->
                        change.consume()
                        travelled = overSlop.coerceAtLeast(0f)
                    } ?: return@awaitEachGesture
                    scope.launch { offset.snapTo(travelled) }
                    val finished = horizontalDrag(drag.id) { change ->
                        travelled = (travelled + change.positionChange().x).coerceAtLeast(0f)
                        change.consume()
                        scope.launch { offset.snapTo(travelled) }
                    }
                    if (finished && travelled >= commit) {
                        scope.launch {
                            offset.animateTo(size.width.toFloat())
                            currentOnBack()
                            offset.snapTo(0f)
                        }
                    } else {
                        scope.launch { offset.animateTo(0f) }
                    }
                }
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(offset.value.roundToInt(), 0) }
        ) {
            content()
        }
    }
}
