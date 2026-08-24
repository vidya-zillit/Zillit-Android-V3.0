package com.zillit.zillitapp.core.attachment.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Interests
import androidx.compose.material.icons.outlined.Rotate90DegreesCcw
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import coil.compose.AsyncImage
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.unit.sp
import com.zillit.zillitapp.R
import com.zillit.zillitapp.core.attachment.PickedMedia
import com.zillit.zillitapp.core.ui.theme.ZillitTheme
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * The image editor: crop, rotate, pencil, text, emoji and shapes.
 *
 * The same tool set as v2's `EditImageActivity` (crop / rotate / text / paint fragments
 * plus the photoeditor library's emoji and stickers), rebuilt on a Compose `Canvas` so
 * annotations stay as objects until Done — see [EditorState].
 *
 * @param onDone receives the flattened result. Rasterising is the caller's job because
 *   only it knows where the file belongs and what the upload will reference.
 */
@Composable
fun ImageEditorScreen(
    media: PickedMedia,
    onDone: (EditorState) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val history = remember { EditorHistory() }
    var state by remember { mutableStateOf(history.current) }

    // Every mutation goes through here so the history and the rendered state cannot drift.
    fun apply(next: EditorState, checkpoint: Boolean = true) {
        history.update(next, checkpoint)
        state = history.current
    }
    var pendingText by remember { mutableStateOf<Offset?>(null) }
    var textDraft by remember { mutableStateOf("") }
    var showEmojiRow by remember { mutableStateOf(false) }

    // Live gesture, not yet committed — drawn on top so the user sees the stroke or shape
    // forming, but not added to the element list until the finger lifts.
    var activeStroke by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var activeShape by remember { mutableStateOf<Pair<Offset, Offset>?>(null) }
    var cropDraft by remember { mutableStateOf<NormalisedRect?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0E1116))
            // No inset padding here: the host dialog fits the system decor, so this
            // column already measures against the usable area. Adding it back is what
            // clipped the bottom bar off the screen.
    ) {
        // Top bar
        Row(
            modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            EditorAction(
                icon = Icons.Filled.Close,
                labelRes = R.string.action_close,
                onClick = onCancel,
            )
            Box(Modifier.weight(1f))
            if (history.canUndo) {
                EditorAction(
                    icon = Icons.AutoMirrored.Outlined.Undo,
                    labelRes = R.string.editor_undo,
                    onClick = { history.undo(); state = history.current },
                )
            }
            if (history.canRedo) {
                EditorAction(
                    icon = Icons.AutoMirrored.Outlined.Redo,
                    labelRes = R.string.editor_redo,
                    onClick = { history.redo(); state = history.current },
                )
            }
            // Delete acts on the selected element, so it only appears with a selection.
            state.selected?.let { selected ->
                EditorAction(
                    icon = Icons.Outlined.DeleteOutline,
                    labelRes = R.string.editor_delete,
                    tint = ZillitTheme.colors.danger,
                    onClick = { apply(state.removing(selected.id)) },
                )
            }
            EditorAction(
                icon = Icons.Filled.Check,
                labelRes = R.string.action_done,
                tint = ZillitTheme.colors.brand,
                onClick = {
                    val finished = cropDraft?.let { state.copy(crop = it.clamped()) } ?: state
                    onDone(finished)
                },
            )
        }

        // Canvas
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(ZillitTheme.spacing.md),
            contentAlignment = Alignment.Center,
        ) {
            EditorCanvas(
                media = media,
                state = state,
                onTap = { normalised ->
                    // An existing element always wins the tap, whatever tool is active —
                    // otherwise tapping a label with the Text tool selected re-opened the
                    // "add text" dialog instead of selecting the label.
                    val hit = state.elements.lastOrNull { it.contains(normalised) }
                    when {
                        hit != null -> apply(
                            state.copy(selectedId = hit.id),
                            checkpoint = false,
                        )

                        state.activeTool == EditorTool.TEXT -> pendingText = normalised

                        // Tapping empty canvas dismisses the handles.
                        else -> apply(state.copy(selectedId = null), checkpoint = false)
                    }
                },
                onDeleteSelected = { state.selected?.let { apply(state.removing(it.id)) } },
                onMoveSelected = { delta ->
                    val selected = state.selected ?: return@EditorCanvas
                    // No checkpoint per frame: one undo must reverse the whole drag, not
                    // a single pixel of it.
                    apply(state.replacing(selected.movedBy(delta)), checkpoint = false)
                },
                onResizeSelected = { factor ->
                    val selected = state.selected ?: return@EditorCanvas
                    apply(state.replacing(selected.scaledBy(factor)), checkpoint = false)
                },
                onGestureCommit = { apply(state, checkpoint = true) },
                activeStroke = activeStroke,
                activeShape = activeShape,
                cropDraft = cropDraft,
                onGestureStart = { normalised ->
                    when (state.activeTool) {
                        EditorTool.PENCIL -> activeStroke = listOf(normalised)
                        EditorTool.SHAPE -> activeShape = normalised to normalised
                        EditorTool.CROP -> cropDraft =
                            NormalisedRect(normalised.x, normalised.y, normalised.x, normalised.y)

                        EditorTool.TEXT -> pendingText = normalised
                        // Pressing empty canvas with no tool active clears the selection,
                        // which is how the handles are dismissed.
                        else -> apply(state.copy(selectedId = null), checkpoint = false)
                    }
                },
                onGestureMove = { normalised ->
                    when (state.activeTool) {
                        EditorTool.PENCIL -> activeStroke = activeStroke + normalised
                        EditorTool.SHAPE -> activeShape = activeShape?.copy(second = normalised)
                        EditorTool.CROP -> cropDraft = cropDraft?.copy(
                            right = normalised.x,
                            bottom = normalised.y,
                        )

                        // Moving is done from the move handle, not by dragging the canvas:
                        // a canvas drag is ambiguous when a drawing tool is active.
                        else -> Unit
                    }
                },
                onGestureEnd = {
                    when (state.activeTool) {
                        EditorTool.PENCIL -> {
                            // A single tap is not a stroke — two points minimum, or every
                            // stray tap on the canvas leaves an invisible dot in the list.
                            if (activeStroke.size > 1) {
                                apply(
                                    state.withElement(
                                        EditorElement.Stroke(
                                            id = nextId(),
                                            points = activeStroke,
                                            color = state.strokeColor,
                                            widthFraction = state.strokeWidthFraction,
                                        ),
                                    ),
                                )
                            }
                            activeStroke = emptyList()
                        }

                        EditorTool.SHAPE -> {
                            activeShape?.let { (from, to) ->
                                if (abs(to.x - from.x) > MIN_DRAG || abs(to.y - from.y) > MIN_DRAG) {
                                    apply(
                                        state.withElement(
                                            EditorElement.Shape(
                                                id = nextId(),
                                                kind = state.shapeKind,
                                                start = from,
                                                end = to,
                                                color = state.strokeColor,
                                                widthFraction = state.strokeWidthFraction,
                                            ),
                                        ),
                                    )
                                }
                            }
                            activeShape = null
                        }

                        // Committing the move as one history entry, so undo reverses the
                        // whole drag rather than a frame of it.
                        else -> if (state.selected != null) apply(state, checkpoint = true)
                    }
                },
            )
        }

        // Colour row — shown only for the tools that draw with a colour.
        if (state.activeTool in setOf(EditorTool.PENCIL, EditorTool.SHAPE, EditorTool.TEXT)) {
            ColorRow(
                selected = state.strokeColor,
                onSelect = { apply(state.copy(strokeColor = it), checkpoint = false) },
            )
        }

        if (state.activeTool == EditorTool.SHAPE) {
            ShapeRow(
                selected = state.shapeKind,
                onSelect = { apply(state.copy(shapeKind = it), checkpoint = false) },
            )
        }

        if (showEmojiRow) {
            EmojiRow(
                onSelect = { emoji ->
                    // Placed centre-canvas and then dragged, rather than requiring a tap
                    // target first: on a phone the middle is always reachable.
                    apply(
                        state.withElement(
                            EditorElement.Emoji(
                                id = nextId(),
                                emoji = emoji,
                                position = Offset(0.5f, 0.5f),
                                sizeFraction = 0.12f,
                            ),
                        ),
                    )
                    showEmojiRow = false
                },
            )
        }

        state.selected?.let { selected ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ZillitTheme.spacing.md),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.editor_resize),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.8f),
                )
                listOf(
                    "−" to 1f / RESIZE_STEP,
                    "+" to RESIZE_STEP,
                ).forEach { (label, factor) ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.12f))
                            .clickable { apply(state.replacing(selected.scaledBy(factor))) }
                            .padding(horizontal = 14.dp, vertical = 2.dp),
                    )
                }
            }
        }

        if (state.activeTool == EditorTool.CROP) {
            CropAspectRow(
                onSelect = { aspect ->
                    cropDraft = aspect.ratio?.let { ratio ->
                        // Centred rect of the requested ratio, assuming a square canvas
                        // in normalised space; the canvas letterboxes it correctly.
                        val height = if (ratio >= 1f) 1f / ratio else 1f
                        val width = if (ratio >= 1f) 1f else ratio
                        NormalisedRect(
                            left = (1f - width) / 2f,
                            top = (1f - height) / 2f,
                            right = (1f + width) / 2f,
                            bottom = (1f + height) / 2f,
                        )
                    } ?: NormalisedRect.FULL
                },
            )
        }

        EditorToolbar(
            active = state.activeTool,
            onToolSelected = { tool ->
                showEmojiRow = tool == EditorTool.EMOJI
                apply(
                    when (tool) {
                        // Rotate is an action, not a mode — tapping it turns the image and
                        // leaves the previous tool selected.
                        EditorTool.ROTATE -> state.rotated()
                        else -> state.copy(
                            activeTool = if (state.activeTool == tool) EditorTool.NONE else tool,
                            // Leaving select mode drops the selection frame.
                            selectedId = if (tool == EditorTool.NONE) state.selectedId else null,
                        )
                    },
                    checkpoint = tool == EditorTool.ROTATE,
                )
            },
        )
    }

    pendingText?.let { position ->
        AlertDialog(
            onDismissRequest = { pendingText = null; textDraft = "" },
            title = { Text(stringResource(R.string.editor_add_text)) },
            text = {
                OutlinedTextField(
                    value = textDraft,
                    onValueChange = { textDraft = it },
                    singleLine = false,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (textDraft.isNotBlank()) {
                            apply(
                                state.withElement(
                                    EditorElement.TextLabel(
                                        id = nextId(),
                                        text = textDraft.trim(),
                                        position = position,
                                        color = state.strokeColor,
                                        sizeFraction = 0.06f,
                                    ),
                                ),
                            )
                        }
                        pendingText = null
                        textDraft = ""
                    },
                ) { Text(stringResource(R.string.action_done)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingText = null; textDraft = "" }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            containerColor = ZillitTheme.colors.surface,
        )
    }
}

@Composable
private fun EditorCanvas(
    media: PickedMedia,
    state: EditorState,
    activeStroke: List<Offset>,
    activeShape: Pair<Offset, Offset>?,
    cropDraft: NormalisedRect?,
    onTap: (Offset) -> Unit,
    onGestureStart: (Offset) -> Unit,
    onGestureMove: (Offset) -> Unit,
    onGestureEnd: () -> Unit,
    onDeleteSelected: () -> Unit,
    onMoveSelected: (Offset) -> Unit,
    onResizeSelected: (Float) -> Unit,
    onGestureCommit: () -> Unit,
) {
    val textMeasurer = rememberTextMeasurer()
    var canvasSize by remember { mutableStateOf(Size.Zero) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ZillitTheme.shapes.medium))
            .background(Color.Black)
            .onSizeChanged { canvasSize = it.toSize() }
            // Taps are a separate detector. `detectDragGestures` only fires once the
            // pointer has actually moved, so placing text — which is a tap, not a drag —
            // did nothing at all, and neither did tapping an element to select it.
            .pointerInput(state.activeTool, state.elements) {
                detectTapGestures { offset -> onTap(offset.normalise(size.toSize())) }
            }
            .pointerInput(state.activeTool) {
                detectDragGestures(
                    onDragStart = { offset -> onGestureStart(offset.normalise(size.toSize())) },
                    onDrag = { change, _ ->
                        onGestureMove(change.position.normalise(size.toSize()))
                    },
                    onDragEnd = { onGestureEnd() },
                    onDragCancel = { onGestureEnd() },
                )
            },
    ) {
        // The image sits under the annotation canvas, which shares its exact bounds —
        // that is what keeps normalised coordinates aligned with what the user sees.
        AsyncImage(
            model = media.localPath,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )

        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvas = size

            state.elements.forEach { element ->
                when (element) {
                    is EditorElement.Stroke -> drawStroke(
                        points = element.points,
                        color = element.color,
                        strokeWidth = element.widthFraction * canvas.width,
                        canvas = canvas,
                    )

                    is EditorElement.Shape -> drawShape(
                        kind = element.kind,
                        start = element.start.denormalise(canvas),
                        end = element.end.denormalise(canvas),
                        color = element.color,
                        strokeWidth = element.widthFraction * canvas.width,
                    )

                    is EditorElement.TextLabel -> {
                        val position = element.position.denormalise(canvas)
                        drawText(
                            textMeasurer = textMeasurer,
                            text = element.text,
                            topLeft = position,
                            style = TextStyle(
                                color = element.color,
                                fontSize = spFor(element.sizeFraction * canvas.height),
                            ),
                        )
                    }

                    is EditorElement.Emoji -> {
                        val position = element.position.denormalise(canvas)
                        drawText(
                            textMeasurer = textMeasurer,
                            text = element.emoji,
                            topLeft = position,
                            style = TextStyle(
                                fontSize = spFor(element.sizeFraction * canvas.height),
                            ),
                        )
                    }
                }
            }

            // In-flight gesture, drawn with the same parameters it will be committed with
            // so what the user sees while dragging is exactly what lands.
            if (activeStroke.size > 1) {
                drawStroke(
                    points = activeStroke,
                    color = state.strokeColor,
                    strokeWidth = state.strokeWidthFraction * canvas.width,
                    canvas = canvas,
                )
            }

            activeShape?.let { (from, to) ->
                drawShape(
                    kind = state.shapeKind,
                    start = from.denormalise(canvas),
                    end = to.denormalise(canvas),
                    color = state.strokeColor,
                    strokeWidth = state.strokeWidthFraction * canvas.width,
                )
            }

            state.selected?.let { selected ->
                val bounds = selected.boundsIn()
                val left = bounds.left * canvas.width
                val top = bounds.top * canvas.height
                val right = bounds.right * canvas.width
                val bottom = bounds.bottom * canvas.height

                drawRect(
                    color = Color.White,
                    topLeft = Offset(left, top),
                    size = Size(right - left, bottom - top),
                    style = Stroke(width = 1.5.dp.toPx()),
                )
                // Corner handles read as "this can be resized"; the buttons beneath do
                // the resizing, because a 2px stroke is not a reliable drag target.
                listOf(
                    Offset(left, top), Offset(right, top),
                    Offset(left, bottom), Offset(right, bottom),
                ).forEach { corner ->
                    drawCircle(Color.White, radius = 5.dp.toPx(), center = corner)
                }
            }

            (cropDraft ?: state.crop)?.let { rect ->
                val left = rect.left * canvas.width
                val top = rect.top * canvas.height
                val right = rect.right * canvas.width
                val bottom = rect.bottom * canvas.height

                // Scrim everything outside the crop so the kept area reads as the subject.
                drawRect(Color.Black.copy(alpha = 0.45f), size = Size(canvas.width, top))
                drawRect(
                    Color.Black.copy(alpha = 0.45f),
                    topLeft = Offset(0f, bottom),
                    size = Size(canvas.width, canvas.height - bottom),
                )
                drawRect(
                    Color.Black.copy(alpha = 0.45f),
                    topLeft = Offset(0f, top),
                    size = Size(left, bottom - top),
                )
                drawRect(
                    Color.Black.copy(alpha = 0.45f),
                    topLeft = Offset(right, top),
                    size = Size(canvas.width - right, bottom - top),
                )

                drawRect(
                    color = Color.White,
                    topLeft = Offset(left, top),
                    size = Size(right - left, bottom - top),
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }

        state.selected?.let { selected ->
            if (canvasSize != Size.Zero) {
                SelectionHandles(
                    bounds = selected.boundsIn(),
                    canvas = canvasSize,
                    onDelete = onDeleteSelected,
                    onMove = onMoveSelected,
                    onResize = onResizeSelected,
                    onCommit = onGestureCommit,
                )
            }
        }

        if (state.rotationDegrees != 0) {
            Text(
                text = "${state.rotationDegrees}°",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(ZillitTheme.spacing.sm)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * Delete, move and resize, pinned to the selection outline.
 *
 * Explicit handles rather than "drag anywhere inside": an annotation is often only a few
 * pixels of stroke, so there is nothing reliable to grab, and with a drawing tool active a
 * press inside the element would otherwise start a new stroke instead of moving it. Each
 * handle is its own touch target with its own gesture, so the three actions never compete.
 */
@Composable
private fun BoxScope.SelectionHandles(
    bounds: NormalisedRect,
    canvas: Size,
    onDelete: () -> Unit,
    onMove: (Offset) -> Unit,
    onResize: (Float) -> Unit,
    onCommit: () -> Unit,
) {
    val density = LocalDensity.current

    val left = bounds.left * canvas.width
    val top = bounds.top * canvas.height
    val right = bounds.right * canvas.width
    val bottom = bounds.bottom * canvas.height

    fun offsetFor(x: Float, y: Float) = with(density) {
        IntOffset(
            (x - HANDLE_SIZE.toPx() / 2f).toInt(),
            (y - HANDLE_SIZE.toPx() / 2f).toInt(),
        )
    }

    // Top-left: delete. Furthest from the resize handle so the destructive action is the
    // hardest of the three to hit by accident.
    HandleButton(
        icon = Icons.Filled.Close,
        labelRes = R.string.editor_delete,
        tint = Color.White,
        background = ZillitTheme.colors.danger,
        modifier = Modifier.offset { offsetFor(left, top) }.clickable(onClick = onDelete),
    )

    // Top-right: move.
    HandleButton(
        icon = Icons.Filled.OpenWith,
        labelRes = R.string.editor_move,
        modifier = Modifier
            .offset { offsetFor(right, top) }
            .pointerInput(bounds, canvas) {
                detectDragGestures(
                    onDrag = { change, drag ->
                        change.consume()
                        // Reported as a normalised delta so the element moves exactly with
                        // the finger regardless of canvas size.
                        onMove(Offset(drag.x / canvas.width, drag.y / canvas.height))
                    },
                    onDragEnd = onCommit,
                )
            },
    )

    // Bottom-right: resize, where a resize handle is conventionally expected.
    HandleButton(
        icon = Icons.Filled.OpenInFull,
        labelRes = R.string.editor_resize,
        modifier = Modifier
            .offset { offsetFor(right, bottom) }
            .pointerInput(bounds, canvas) {
                detectDragGestures(
                    onDrag = { change, drag ->
                        change.consume()
                        // Diagonal distance drives the scale: dragging away from the
                        // centre grows, towards it shrinks.
                        val span = maxOf(right - left, bottom - top).coerceAtLeast(1f)
                        onResize(1f + (drag.x + drag.y) / span)
                    },
                    onDragEnd = onCommit,
                )
            },
    )
}

@Composable
private fun HandleButton(
    icon: ImageVector,
    labelRes: Int,
    modifier: Modifier = Modifier,
    tint: Color = Color.Black,
    background: Color = Color.White,
) {
    Box(
        modifier = modifier
            .size(HANDLE_SIZE)
            .background(background, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = stringResource(labelRes),
            tint = tint,
            modifier = Modifier.size(HANDLE_ICON_SIZE),
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawStroke(
    points: List<Offset>,
    color: Color,
    strokeWidth: Float,
    canvas: Size,
) {
    val path = Path()
    points.forEachIndexed { index, point ->
        val p = point.denormalise(canvas)
        if (index == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
    }
    drawPath(path, color, style = Stroke(width = strokeWidth, cap = StrokeCap.Round))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawShape(
    kind: ShapeKind,
    start: Offset,
    end: Offset,
    color: Color,
    strokeWidth: Float,
) {
    val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)
    val topLeft = Offset(minOf(start.x, end.x), minOf(start.y, end.y))
    val size = Size(abs(end.x - start.x), abs(end.y - start.y))

    when (kind) {
        ShapeKind.RECTANGLE -> drawRect(color, topLeft, size, style = stroke)
        ShapeKind.OVAL -> drawOval(color, topLeft, size, style = stroke)
        ShapeKind.LINE -> drawLine(color, start, end, strokeWidth, StrokeCap.Round)
        ShapeKind.ARROW -> {
            drawLine(color, start, end, strokeWidth, StrokeCap.Round)
            // Head drawn from the line's own angle, so it points correctly in any
            // direction rather than only down-right.
            val angle = atan2(end.y - start.y, end.x - start.x)
            val headLength = strokeWidth * 4f
            listOf(angle - ARROW_SPREAD, angle + ARROW_SPREAD).forEach { branch ->
                drawLine(
                    color,
                    end,
                    Offset(
                        end.x - headLength * cos(branch),
                        end.y - headLength * sin(branch),
                    ),
                    strokeWidth,
                    StrokeCap.Round,
                )
            }
        }
    }
}

@Composable
private fun EditorToolbar(active: EditorTool, onToolSelected: (EditorTool) -> Unit) {
    val tools = listOf(
        EditorTool.CROP to (Icons.Outlined.Crop to R.string.editor_crop),
        EditorTool.ROTATE to (Icons.Outlined.Rotate90DegreesCcw to R.string.editor_rotate),
        EditorTool.PENCIL to (Icons.Outlined.Interests to R.string.editor_pencil),
        EditorTool.TEXT to (Icons.Outlined.TextFields to R.string.editor_text),
        EditorTool.EMOJI to (Icons.Outlined.EmojiEmotions to R.string.editor_emoji),
        EditorTool.SHAPE to (Icons.Outlined.Interests to R.string.editor_shape),
    )

    // Scrollable rather than SpaceEvenly: six labelled tools do not fit across a narrow
    // phone, and squeezing them truncates the labels to single letters.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(
                horizontal = ZillitTheme.spacing.md,
                vertical = ZillitTheme.spacing.sm,
            ),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tools.forEach { (tool, iconAndLabel) ->
            val (icon, labelRes) = iconAndLabel
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.clickable { onToolSelected(tool) },
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .background(
                            if (active == tool) {
                                ZillitTheme.colors.brand
                            } else {
                                Color.White.copy(alpha = 0.12f)
                            },
                            CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = stringResource(labelRes),
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Text(
                    text = stringResource(labelRes),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.8f),
                )
            }
        }
    }
}

@Composable
private fun ColorRow(selected: Color, onSelect: (Color) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = ZillitTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.sm),
    ) {
        items(EDITOR_COLORS) { color ->
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(color, CircleShape)
                    .then(
                        if (color == selected) {
                            Modifier.border(2.dp, Color.White, CircleShape)
                        } else {
                            Modifier
                        },
                    )
                    .clickable { onSelect(color) },
            )
        }
    }
}

@Composable
private fun ShapeRow(selected: ShapeKind, onSelect: (ShapeKind) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = ZillitTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ShapeKind.entries.forEach { kind ->
            Text(
                text = kind.name.lowercase().replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.labelMedium,
                color = if (kind == selected) ZillitTheme.colors.brand else Color.White,
                modifier = Modifier
                    .clip(RoundedCornerShape(ZillitTheme.shapes.pill))
                    .background(Color.White.copy(alpha = 0.10f))
                    .clickable { onSelect(kind) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun CropAspectRow(onSelect: (CropAspect) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = ZillitTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        CropAspect.entries.forEach { aspect ->
            Text(
                text = when (aspect) {
                    CropAspect.FREE -> "Free"
                    CropAspect.SQUARE -> "1:1"
                    CropAspect.RATIO_4_3 -> "4:3"
                    CropAspect.RATIO_16_9 -> "16:9"
                    CropAspect.RATIO_3_4 -> "3:4"
                },
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                modifier = Modifier
                    .clip(RoundedCornerShape(ZillitTheme.shapes.pill))
                    .background(Color.White.copy(alpha = 0.10f))
                    .clickable { onSelect(aspect) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun EmojiRow(onSelect: (String) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = ZillitTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.sm),
    ) {
        items(EDITOR_EMOJI) { emoji ->
            Text(
                text = emoji,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.clickable { onSelect(emoji) }.padding(4.dp),
            )
        }
    }
}

@Composable
private fun EditorAction(
    icon: ImageVector,
    labelRes: Int,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .background(Color.White.copy(alpha = 0.12f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = stringResource(labelRes),
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
    }
}

private fun Offset.normalise(canvas: Size) = Offset(
    x = (x / canvas.width).coerceIn(0f, 1f),
    y = (y / canvas.height).coerceIn(0f, 1f),
)

private fun Offset.denormalise(canvas: Size) = Offset(x * canvas.width, y * canvas.height)

/**
 * Canvas pixels to sp.
 *
 * Text on the canvas is sized as a fraction of the image, not in sp, so it stays in
 * proportion when the same edit is viewed on a different screen. The divisor converts to
 * the sp scale without depending on the device's font-scale setting, which must not change
 * where an annotation sits on a photo.
 */
private fun spFor(pixels: Float) = (pixels / 2.5f).sp

private var idCounter = 0L

private fun nextId(): Long = ++idCounter

/** Fraction of the canvas below which a drag is treated as a stray tap. */
private const val MIN_DRAG = 0.01f

private const val ARROW_SPREAD = 0.5f

/** One tap scales by this much; small enough to be controllable, large enough to feel. */
private const val RESIZE_STEP = 1.2f

/** Comfortably above the 48dp minimum once the icon's padding is counted. */
private val HANDLE_SIZE = 32.dp

private val HANDLE_ICON_SIZE = 16.dp

private val EDITOR_COLORS = listOf(
    Color(0xFFFC9404),
    Color.White,
    Color.Black,
    Color(0xFFE53935),
    Color(0xFF2E9E5B),
    Color(0xFF1353D1),
    Color(0xFFFFEB3B),
    Color(0xFF9C27B0),
)

private val EDITOR_EMOJI = listOf(
    "😀", "😂", "😍", "👍", "👏", "🔥", "✅", "❌", "⚠️", "❤️", "⭐", "📌", "🎬", "🎥", "📷",
)