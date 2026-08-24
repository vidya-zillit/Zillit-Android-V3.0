package com.zillit.zillitapp.core.attachment.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import androidx.compose.ui.geometry.Offset
import com.zillit.zillitapp.core.attachment.PickedMedia
import com.zillit.zillitapp.core.logging.ZillitLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Flattens an [EditorState] onto the image and writes a new file.
 *
 * Runs **once**, on Done. Annotations are held as objects until this point (see
 * [EditorState]) so they stay movable, resizable and removable while editing; this is the
 * only place pixels are touched.
 *
 * The output is a **new file**, never an overwrite. The original is the user's own photo
 * in their gallery — editing a copy for sending must not alter it.
 */
@Singleton
class EditorRasterizer @Inject constructor() {

    suspend fun render(source: PickedMedia, state: EditorState): PickedMedia? =
        withContext(Dispatchers.IO) {
            runCatching {
                val original = BitmapFactory.decodeFile(source.localPath)
                    ?: return@runCatching null

                // Order matters: crop in the original's coordinate space, then rotate, then
                // draw annotations — which are positioned relative to what the user saw,
                // i.e. the cropped and rotated result.
                val cropped = state.crop?.let { original.cropped(it) } ?: original
                val rotated = if (state.rotationDegrees != 0) {
                    cropped.rotated(state.rotationDegrees)
                } else {
                    cropped
                }

                val output = rotated.copy(Bitmap.Config.ARGB_8888, true)
                    ?: return@runCatching null

                Canvas(output).drawAnnotations(state.elements, output.width, output.height)

                val target = File(
                    File(source.localPath).parentFile,
                    "edited_${System.currentTimeMillis()}_${File(source.localPath).name}",
                )
                target.outputStream().use { out ->
                    output.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                }

                // Intermediates are recycled, but never `original` when nothing changed —
                // it is the same instance as `output`'s source and would be freed twice.
                if (cropped !== original) cropped.recycle()
                if (rotated !== cropped && rotated !== original) rotated.recycle()
                original.recycle()
                output.recycle()

                source.copy(
                    localPath = target.absolutePath,
                    fileName = target.name,
                    sizeBytes = target.length(),
                    mimeType = "image/jpeg",
                )
            }.onFailure {
                ZillitLog.w(TAG, "Rasterise failed: ${it.message}")
            }.getOrNull()
        }

    private fun Bitmap.cropped(rect: NormalisedRect): Bitmap {
        val clamped = rect.clamped()
        val left = (clamped.left * width).toInt().coerceIn(0, width - 1)
        val top = (clamped.top * height).toInt().coerceIn(0, height - 1)
        val right = (clamped.right * width).toInt().coerceIn(left + 1, width)
        val bottom = (clamped.bottom * height).toInt().coerceIn(top + 1, height)
        return Bitmap.createBitmap(this, left, top, right - left, bottom - top)
    }

    private fun Bitmap.rotated(degrees: Int): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    }

    /**
     * Draws the display list at output resolution.
     *
     * Every element is stored in 0f..1f image space, so a stroke drawn on a 1080px-wide
     * canvas lands in the same place on a 4000px original — which is exactly why the
     * editor stores fractions rather than screen pixels.
     */
    private fun Canvas.drawAnnotations(
        elements: List<EditorElement>,
        canvasWidth: Int,
        canvasHeight: Int,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        elements.forEach { element ->
            when (element) {
                is EditorElement.Stroke -> {
                    paint.reset()
                    paint.isAntiAlias = true
                    paint.style = Paint.Style.STROKE
                    paint.strokeCap = Paint.Cap.ROUND
                    paint.strokeJoin = Paint.Join.ROUND
                    paint.color = element.color.toArgb()
                    paint.strokeWidth = element.widthFraction * canvasWidth

                    val path = Path()
                    element.points.forEachIndexed { index, point ->
                        val x = point.x * canvasWidth
                        val y = point.y * canvasHeight
                        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, paint)
                }

                is EditorElement.Shape -> {
                    paint.reset()
                    paint.isAntiAlias = true
                    paint.style = Paint.Style.STROKE
                    paint.strokeCap = Paint.Cap.ROUND
                    paint.color = element.color.toArgb()
                    paint.strokeWidth = element.widthFraction * canvasWidth

                    val start = Offset(element.start.x * canvasWidth, element.start.y * canvasHeight)
                    val end = Offset(element.end.x * canvasWidth, element.end.y * canvasHeight)
                    drawShape(element.kind, start, end, paint)
                }

                is EditorElement.TextLabel -> {
                    paint.reset()
                    paint.isAntiAlias = true
                    paint.style = Paint.Style.FILL
                    paint.color = element.color.toArgb()
                    paint.textSize = element.sizeFraction * canvasHeight
                    // Baseline, not top: drawText anchors at the baseline, so without the
                    // ascent the label sits one line higher than where it was placed.
                    val y = element.position.y * canvasHeight - paint.fontMetrics.ascent
                    drawText(element.text, element.position.x * canvasWidth, y, paint)
                }

                is EditorElement.Emoji -> {
                    paint.reset()
                    paint.isAntiAlias = true
                    paint.textSize = element.sizeFraction * canvasHeight
                    val y = element.position.y * canvasHeight - paint.fontMetrics.ascent
                    drawText(element.emoji, element.position.x * canvasWidth, y, paint)
                }
            }
        }
    }

    private fun Canvas.drawShape(kind: ShapeKind, start: Offset, end: Offset, paint: Paint) {
        val left = min(start.x, end.x)
        val top = min(start.y, end.y)
        val right = maxOf(start.x, end.x)
        val bottom = maxOf(start.y, end.y)

        when (kind) {
            ShapeKind.RECTANGLE -> drawRect(left, top, right, bottom, paint)
            ShapeKind.OVAL -> drawOval(left, top, right, bottom, paint)
            ShapeKind.LINE -> drawLine(start.x, start.y, end.x, end.y, paint)
            ShapeKind.ARROW -> {
                drawLine(start.x, start.y, end.x, end.y, paint)
                // Head derived from the line's own angle, so it points correctly in any
                // direction rather than only down-right.
                val angle = atan2(end.y - start.y, end.x - start.x)
                val headLength = paint.strokeWidth * 4f
                listOf(angle - ARROW_SPREAD, angle + ARROW_SPREAD).forEach { branch ->
                    drawLine(
                        end.x,
                        end.y,
                        end.x - headLength * cos(branch),
                        end.y - headLength * sin(branch),
                        paint,
                    )
                }
            }
        }
    }

    private fun androidx.compose.ui.graphics.Color.toArgb(): Int =
        android.graphics.Color.argb(
            (alpha * 255).toInt(),
            (red * 255).toInt(),
            (green * 255).toInt(),
            (blue * 255).toInt(),
        )

    private companion object {
        const val TAG = "EditorRasterizer"

        /** High enough that annotations stay crisp, low enough to keep uploads sane. */
        const val JPEG_QUALITY = 92

        const val ARROW_SPREAD = 0.5f
    }
}
