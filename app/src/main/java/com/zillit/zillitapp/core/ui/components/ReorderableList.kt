package com.zillit.zillitapp.core.ui.components

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Drag-to-reorder for a `LazyColumn`.
 *
 * Compose ships no equivalent of `ItemTouchHelper`, so this is the piece every reorderable
 * list in the app would otherwise write for itself. The rules list needs it because a rule's
 * **array position is its priority** — the order is the feature, not a preference.
 *
 * The contract mirrors the view world's, deliberately: [onMove] fires continuously as the
 * dragged row crosses its neighbours so the list reorders live and locally, and [onDrop]
 * fires once at the end for the caller to persist. That is what keeps a ten-step reorder to
 * one request instead of ten.
 *
 * @param onMove called with (from, to) each time the dragged item passes another. Reorder
 *   your own list here; this class holds no copy of it.
 * @param onDrop called once when the finger lifts, and only if anything actually moved.
 */
class ReorderableListState internal constructor(
    val listState: LazyListState,
    private val scope: CoroutineScope,
    private val onMove: (from: Int, to: Int) -> Unit,
    private val onDrop: () -> Unit,
) {
    /**
     * The row as it was when the finger went down.
     *
     * Held for the whole gesture and never re-read. The previous version re-measured the
     * dragged row every frame and patched the offset after each swap — but the list has not
     * re-laid-out yet at that point, so the correction used stale positions, the maths went
     * out of step, and a drag could only ever move a row one slot. Anchoring to the start
     * means the finger's travel is the only thing that matters.
     */
    private var anchor: LazyListItemInfo? = null

    /** Where the dragged row has got to now. Follows each swap. */
    internal var draggingIndex by mutableIntStateOf(NOT_DRAGGING)
        private set

    /** Total finger travel since the press, in pixels. */
    private var travelled by mutableFloatStateOf(0f)

    private var movedDuringDrag = false

    /** True while [index] is the row under the finger, so the caller can lift it visually. */
    fun isDragging(index: Int): Boolean = index == draggingIndex

    /**
     * How far to translate the dragged row from the slot it currently occupies.
     *
     * Computed rather than accumulated: as the row is swapped along the list its slot moves
     * under it, so the visual offset is the distance from where it started plus the travel,
     * minus where its slot is now. That keeps the row exactly under the finger however many
     * positions it has crossed.
     */
    internal val dragOffset: Float
        get() {
            val start = anchor ?: return 0f
            val slot = visibleItem(draggingIndex) ?: return travelled
            return start.offset + travelled - slot.offset
        }

    internal fun onDragStart(index: Int) {
        anchor = visibleItem(index)
        draggingIndex = index
        travelled = 0f
        movedDuringDrag = false
    }

    internal fun onDrag(delta: Float) {
        if (draggingIndex == NOT_DRAGGING) return
        travelled += delta

        val start = anchor ?: return

        // Where the finger has carried the row's middle to. Derived from the slot it was
        // picked up in plus total travel, so it depends only on the gesture — never on
        // where the row's slot has since moved to. That independence is the whole fix: the
        // previous version worked out direction by comparing against the row's *current*
        // slot, which moves with every swap, so after one swap the comparison collapsed and
        // the row would not travel any further.
        val centre = start.offset + travelled + start.size / 2f

        // The row the middle is now over. Once the swap happens this becomes the dragged
        // row's own slot, which is excluded, so nothing oscillates — the next move waits
        // for the finger to reach the slot after it.
        val target = listState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            item.index != draggingIndex &&
                centre >= item.offset &&
                centre <= item.offset + item.size
        }

        if (target != null) {
            onMove(draggingIndex, target.index)
            draggingIndex = target.index
            movedDuringDrag = true
        }

        autoScroll(centre)
    }

    internal fun onDragEnd() {
        val moved = movedDuringDrag
        draggingIndex = NOT_DRAGGING
        anchor = null
        travelled = 0f
        movedDuringDrag = false
        if (moved) onDrop()
    }

    /** Keeps dragging useful past the fold — without it a long list cannot be reordered. */
    private fun autoScroll(centre: Float) {
        val info = listState.layoutInfo
        val top = info.viewportStartOffset + EDGE_ZONE
        val bottom = info.viewportEndOffset - EDGE_ZONE

        val amount = when {
            centre < top -> -SCROLL_STEP
            centre > bottom -> SCROLL_STEP
            else -> return
        }
        scope.launch { listState.scrollBy(amount) }
    }

    private fun visibleItem(index: Int) =
        listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }

    private companion object {
        const val NOT_DRAGGING = -1
        const val EDGE_ZONE = 120
        const val SCROLL_STEP = 14f
    }
}

/**
 * Remembers the drag state for one list.
 *
 * @param onMove called for every swap during the gesture, so the list stays under the finger.
 * @param onDrop called once when the finger lifts, and only if anything actually moved —
 *   the place to persist, rather than saving once per crossing.
 */
@Composable
fun rememberReorderableListState(
    listState: LazyListState,
    onMove: (from: Int, to: Int) -> Unit,
    onDrop: () -> Unit,
): ReorderableListState {
    val scope = rememberCoroutineScope()

    // The state object is remembered across recompositions, so it must not capture the
    // callbacks directly: it would hold the lambdas from the *first* composition, and those
    // close over the list as it was then. Every swap would recompute from the original
    // order, which looked exactly like a row that could only ever move one position however
    // far it was dragged.
    val currentMove = rememberUpdatedState(onMove)
    val currentDrop = rememberUpdatedState(onDrop)

    return remember(listState) {
        ReorderableListState(
            listState = listState,
            scope = scope,
            onMove = { from, to -> currentMove.value(from, to) },
            onDrop = { currentDrop.value() },
        )
    }
}

/**
 * Makes this row draggable after a long press.
 *
 * Applied to the row itself rather than to a handle, matching v2 — a handle is a 24dp target
 * on a list whose rows are the real targets, and long-press is what people try first.
 */
fun Modifier.reorderable(state: ReorderableListState, index: Int): Modifier = this
    .zIndex(if (state.isDragging(index)) 1f else 0f)
    .graphicsLayer {
        if (state.isDragging(index)) {
            translationY = state.dragOffset
            // A small lift, so the row reads as picked up rather than as mis-drawn.
            shadowElevation = 8f
            alpha = 0.95f
        }
    }

/** The gesture half, separated so the caller can put it on a handle instead if it wants. */
@Composable
fun Modifier.reorderableDragHandle(
    state: ReorderableListState,
    index: Int,
): Modifier {
    val haptics = LocalHapticFeedback.current

    // Read through a holder rather than captured directly: the gesture below must not be
    // keyed on the index, and the lambda would otherwise keep the index the row had when
    // the gesture was set up.
    val currentIndex = rememberUpdatedState(index)

    // Keyed on `Unit`, deliberately.
    //
    // Keying on `index` restarts this block whenever the row's index changes — which is
    // exactly what a reorder does. The first swap moved the row from 5 to 4, the block
    // restarted, and the in-flight gesture was cancelled: the finger kept travelling and
    // nothing more arrived. That is why a row could only ever move one position.
    //
    // The list is keyed, so Compose moves this node rather than recreating it, and the
    // gesture survives the row changing places. The index is only needed at the moment of
    // the press; after that the state tracks where the row has got to.
    return pointerInput(Unit) {
        detectDragGesturesAfterLongPress(
            onDragStart = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                state.onDragStart(currentIndex.value)
            },
            onDrag = { change, amount ->
                change.consume()
                state.onDrag(amount.y)
            },
            onDragEnd = state::onDragEnd,
            onDragCancel = state::onDragEnd,
        )
    }
}
