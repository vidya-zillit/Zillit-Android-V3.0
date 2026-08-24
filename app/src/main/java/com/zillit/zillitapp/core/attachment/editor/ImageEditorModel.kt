package com.zillit.zillitapp.core.attachment.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color

/**
 * The editor's document model.
 *
 * Annotations are kept as **objects, not pixels**. v2 (via `photoeditor`) draws each tool
 * straight onto the bitmap, so undo is a stack of bitmaps — memory-hungry on a 12MP photo
 — and nothing can be moved or recoloured after it is placed. Keeping a display list means
 * undo is popping an item, an element stays editable until the user commits, and the
 * bitmap is rasterised exactly once, on Done.
 */

/** Which tool the toolbar has active. */
enum class EditorTool { NONE, CROP, ROTATE, PENCIL, TEXT, EMOJI, SHAPE }

/** The shapes v2's paint fragment offers. */
enum class ShapeKind { RECTANGLE, OVAL, LINE, ARROW }

/**
 * One annotation.
 *
 * Coordinates are **normalised 0f..1f against the image**, not screen pixels, so an
 * annotation stays where the user put it when the canvas is measured differently — after
 * a rotation, on a tablet, or when the same edit is re-opened.
 */
sealed interface EditorElement {
    val id: Long

    data class Stroke(
        override val id: Long,
        val points: List<Offset>,
        val color: Color,
        /** Fraction of the canvas width, so a stroke scales with the image. */
        val widthFraction: Float,
    ) : EditorElement

    data class Shape(
        override val id: Long,
        val kind: ShapeKind,
        val start: Offset,
        val end: Offset,
        val color: Color,
        val widthFraction: Float,
    ) : EditorElement

    data class TextLabel(
        override val id: Long,
        val text: String,
        val position: Offset,
        val color: Color,
        /** Fraction of canvas height. */
        val sizeFraction: Float,
    ) : EditorElement

    data class Emoji(
        override val id: Long,
        val emoji: String,
        val position: Offset,
        val sizeFraction: Float,
    ) : EditorElement
}

/**
 * Everything the editor is currently holding.
 *
 * @param rotationDegrees always a multiple of 90. Applied at rasterise time rather than
 *   by rewriting the bitmap on each tap, so rotating four times costs nothing and loses
 *   no quality.
 * @param crop normalised rect over the *unrotated* image, or null for the full frame.
 */
data class EditorState(
    val elements: List<EditorElement> = emptyList(),
    val rotationDegrees: Int = 0,
    val crop: NormalisedRect? = null,
    val activeTool: EditorTool = EditorTool.NONE,
    val strokeColor: Color = Color(0xFFFC9404),
    val strokeWidthFraction: Float = 0.008f,
    val shapeKind: ShapeKind = ShapeKind.RECTANGLE,
    /** The element being moved, resized or about to be deleted. */
    val selectedId: Long? = null,
) {
    val isDirty: Boolean
        get() = elements.isNotEmpty() || rotationDegrees != 0 || crop != null

    val selected: EditorElement? get() = elements.firstOrNull { it.id == selectedId }

    fun withElement(element: EditorElement) =
        copy(elements = elements + element, selectedId = element.id)

    fun replacing(element: EditorElement) = copy(
        elements = elements.map { if (it.id == element.id) element else it },
    )

    fun removing(id: Long) = copy(
        elements = elements.filterNot { it.id == id },
        selectedId = null,
    )

    fun rotated() = copy(rotationDegrees = (rotationDegrees + 90) % 360)
}

/**
 * Undo/redo over whole [EditorState] snapshots.
 *
 * Snapshots rather than a command list: the state is small — a handful of elements plus a
 * crop and a rotation — so storing it whole makes every action undoable for free, including
 * ones a command list would have to model separately (moving an element, resizing it,
 * changing its colour). A bitmap-per-step history, which is what a paint-directly editor is
 * forced into, would cost megabytes per step on a 12MP photo.
 */
class EditorHistory(initial: EditorState = EditorState()) {

    private val undoStack = ArrayDeque<EditorState>()
    private val redoStack = ArrayDeque<EditorState>()

    var current: EditorState = initial
        private set

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /**
     * Records a change.
     *
     * @param checkpoint false for transient updates — every frame of a drag would
     *   otherwise become its own undo step, and one undo would move the element a single
     *   pixel. The gesture commits one checkpoint when the finger lifts.
     */
    fun update(next: EditorState, checkpoint: Boolean = true) {
        if (checkpoint) {
            undoStack.addLast(current)
            if (undoStack.size > MAX_HISTORY) undoStack.removeFirst()
            // A new action invalidates the redo branch, as in any editor.
            redoStack.clear()
        }
        current = next
    }

    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(current)
        current = previous
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(current)
        current = next
    }

    private companion object {
        /** Deep enough to cover a working session, bounded so it cannot grow forever. */
        const val MAX_HISTORY = 50
    }
}

/** A rectangle in 0f..1f image space. */
data class NormalisedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun clamped(): NormalisedRect = NormalisedRect(
        left = left.coerceIn(0f, 1f),
        top = top.coerceIn(0f, 1f),
        right = right.coerceIn(0f, 1f),
        bottom = bottom.coerceIn(0f, 1f),
    )

    companion object {
        val FULL = NormalisedRect(0f, 0f, 1f, 1f)
    }
}

/** Crop presets, matching what v2's crop fragment offers. */
enum class CropAspect(val ratio: Float?) {
    FREE(null),
    SQUARE(1f),
    RATIO_4_3(4f / 3f),
    RATIO_16_9(16f / 9f),
    RATIO_3_4(3f / 4f),
}

/**
 * Where an element sits, for hit-testing and for drawing its selection frame.
 *
 * Returned in normalised space so it can be compared directly against a normalised touch
 * point, whatever the canvas is measured at.
 */
fun EditorElement.boundsIn(): NormalisedRect = when (this) {
    is EditorElement.Stroke -> {
        val xs = points.map { it.x }
        val ys = points.map { it.y }
        NormalisedRect(
            left = (xs.minOrNull() ?: 0f) - widthFraction,
            top = (ys.minOrNull() ?: 0f) - widthFraction,
            right = (xs.maxOrNull() ?: 0f) + widthFraction,
            bottom = (ys.maxOrNull() ?: 0f) + widthFraction,
        )
    }

    is EditorElement.Shape -> NormalisedRect(
        left = minOf(start.x, end.x) - widthFraction,
        top = minOf(start.y, end.y) - widthFraction,
        right = maxOf(start.x, end.x) + widthFraction,
        bottom = maxOf(start.y, end.y) + widthFraction,
    )

    // Text and emoji are anchored top-left, so the box extends right and down. Width is
    // approximated from the character count — good enough to grab, and avoids measuring
    // text on every hit-test.
    is EditorElement.TextLabel -> NormalisedRect(
        left = position.x,
        top = position.y,
        right = position.x + sizeFraction * text.length * TEXT_WIDTH_RATIO,
        bottom = position.y + sizeFraction,
    )

    is EditorElement.Emoji -> NormalisedRect(
        left = position.x,
        top = position.y,
        right = position.x + sizeFraction,
        bottom = position.y + sizeFraction,
    )
}

/** Moves an element by a normalised delta. */
fun EditorElement.movedBy(delta: Offset): EditorElement = when (this) {
    is EditorElement.Stroke -> copy(points = points.map { it + delta })
    is EditorElement.Shape -> copy(start = start + delta, end = end + delta)
    is EditorElement.TextLabel -> copy(position = position + delta)
    is EditorElement.Emoji -> copy(position = position + delta)
}

/**
 * Scales an element about its own centre.
 *
 * About the centre rather than the top-left so a resize feels like it grows in place —
 * anchoring at the corner makes the element appear to slide away as it grows.
 */
fun EditorElement.scaledBy(factor: Float): EditorElement {
    val safe = factor.coerceIn(MIN_SCALE_STEP, MAX_SCALE_STEP)
    val bounds = boundsIn()
    val centre = Offset(
        (bounds.left + bounds.right) / 2f,
        (bounds.top + bounds.bottom) / 2f,
    )

    fun scalePoint(point: Offset) = Offset(
        centre.x + (point.x - centre.x) * safe,
        centre.y + (point.y - centre.y) * safe,
    )

    return when (this) {
        is EditorElement.Stroke -> copy(
            points = points.map(::scalePoint),
            widthFraction = widthFraction * safe,
        )

        is EditorElement.Shape -> copy(
            start = scalePoint(start),
            end = scalePoint(end),
        )

        is EditorElement.TextLabel -> copy(sizeFraction = sizeFraction * safe)
        is EditorElement.Emoji -> copy(sizeFraction = sizeFraction * safe)
    }
}

/** True when a normalised touch lands on this element. */
fun EditorElement.contains(point: Offset): Boolean {
    val bounds = boundsIn()
    // Padded so a thin stroke or a small emoji is still grabbable with a fingertip.
    return point.x >= bounds.left - TOUCH_SLOP &&
        point.x <= bounds.right + TOUCH_SLOP &&
        point.y >= bounds.top - TOUCH_SLOP &&
        point.y <= bounds.bottom + TOUCH_SLOP
}

/** Rough glyph width as a fraction of height. Only used for hit-testing. */
private const val TEXT_WIDTH_RATIO = 0.55f

/** A fingertip is far wider than a 2px stroke. */
private const val TOUCH_SLOP = 0.03f

private const val MIN_SCALE_STEP = 0.5f

private const val MAX_SCALE_STEP = 2f
