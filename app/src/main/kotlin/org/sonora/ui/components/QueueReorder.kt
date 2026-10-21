// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.ui.components

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.Box

/**
 * Drag-to-reorder for the rows of a [LazyListState]. [keys] are the item keys of the reorderable
 * rows, in order; [onMove] gets the position a row started at and the one it was dragged to.
 */
class ReorderState internal constructor(
    private val listState: LazyListState,
    private val keys: () -> List<Any>,
    private val onMove: (from: Int, to: Int) -> Unit,
) {
    var draggedKey: Any? by mutableStateOf(null)
        private set
    private var delta by mutableFloatStateOf(0f)

    internal fun start(key: Any) {
        draggedKey = key
        delta = 0f
    }

    internal fun drag(dy: Float) {
        val key = draggedKey ?: return
        delta += dy
        val order = keys()
        val index = order.indexOf(key)
        val visible = listState.layoutInfo.visibleItemsInfo
        val current = visible.firstOrNull { it.key == key } ?: return
        val centre = current.offset + current.size / 2f + delta
        val neighbourIndex = if (delta > 0) index + 1 else index - 1
        val neighbour = order.getOrNull(neighbourIndex)?.let { n -> visible.firstOrNull { it.key == n } } ?: return
        val crossed = if (delta > 0) centre > neighbour.offset + neighbour.size / 2f else centre < neighbour.offset + neighbour.size / 2f
        if (!crossed) return
        onMove(index, neighbourIndex)
        // The row's layout position jumps by the neighbour's size once the list reorders; keep it under the finger.
        delta -= if (delta > 0) neighbour.size else -neighbour.size
    }

    internal fun end() {
        draggedKey = null
        delta = 0f
    }

    /** Lifts the dragged row above its neighbours and follows the finger. */
    fun Modifier.reorderable(key: Any): Modifier =
        if (key != draggedKey) this else zIndex(1f).graphicsLayer { translationY = delta }
}

@Composable
fun rememberReorderState(listState: LazyListState, keys: () -> List<Any>, onMove: (from: Int, to: Int) -> Unit): ReorderState {
    val currentKeys = rememberUpdatedState(keys)
    val currentOnMove = rememberUpdatedState(onMove)
    return remember(listState) { ReorderState(listState, { currentKeys.value() }, { f, t -> currentOnMove.value(f, t) }) }
}

/**
 * The grip on a queued row. Dragging it reorders; screen readers get "Move up" and "Move down"
 * (only the ones that apply, per [canMoveUp] and [canMoveDown]).
 */
@Composable
fun QueueDragHandle(
    title: String,
    key: Any,
    state: ReorderState,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(48.dp)
            .pointerInput(key) {
                detectDragGestures(
                    onDragStart = { state.start(key) },
                    onDrag = { change, amount -> change.consume(); state.drag(amount.y) },
                    onDragEnd = state::end,
                    onDragCancel = state::end,
                )
            }
            .semantics {
                contentDescription = "Reorder $title"
                customActions = buildList {
                    if (canMoveUp) add(CustomAccessibilityAction("Move up") { onMoveUp(); true })
                    if (canMoveDown) add(CustomAccessibilityAction("Move down") { onMoveDown(); true })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.DragHandle, contentDescription = null, Modifier.alpha(0.7f))
    }
}
