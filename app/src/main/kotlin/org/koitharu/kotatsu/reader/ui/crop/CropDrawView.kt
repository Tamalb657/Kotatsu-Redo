package org.koitharu.kotatsu.reader.ui.crop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * An image editor view for the crop & save feature: shows a page, lets the user
 * drag a crop rectangle and paint opaque strokes over it (all coordinates are kept
 * in image space, so the export does not depend on the view size or zoom).
 */
class CropDrawView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
) : View(context, attrs) {

	enum class Mode {
		CROP, DRAW, PICK_COLOR,
	}

	class Stroke(
		val color: Int,
		val width: Float,
		val points: MutableList<Point>,
	)

	class Point(val x: Float, val y: Float)

	var bitmap: Bitmap? = null
		private set

	var mode = Mode.CROP
		set(value) {
			field = value
			invalidate()
		}

	/** Colour used for new strokes. */
	var strokeColor = Color.BLACK

	/** Stroke width in image pixels. */
	var strokeWidth = 12f

	var onColorPicked: ((Int) -> Unit)? = null

	val cropRect = RectF()
	val strokes = ArrayList<Stroke>()

	private val viewMatrix = Matrix()
	private val inverseMatrix = Matrix()
	private var bitmapRect = RectF()

	private val dimPaint = Paint().apply {
		color = 0x99000000.toInt()
	}
	private val rectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE
		color = Color.WHITE
		strokeWidth = 2f
	}
	private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.FILL
		color = Color.WHITE
	}
	private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE
		strokeCap = Paint.Cap.ROUND
		strokeJoin = Paint.Join.ROUND
	}
	private val samplePaint = Paint()

	private var activeStroke: Stroke? = null
	private var dragHandle: Handle? = null
	private var dragStart = Point(0f, 0f)
	private var dragRectStart = RectF()

	private enum class Handle {
		TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, MOVE,
	}

	fun setBitmap(value: Bitmap) {
		bitmap = value
		cropRect.set(0f, 0f, value.width.toFloat(), value.height.toFloat())
		strokes.clear()
		activeStroke = null
		invalidate()
	}

	fun undo() {
		strokes.removeLastOrNull()
		invalidate()
	}

	fun resetCrop() {
		val bmp = bitmap ?: return
		cropRect.set(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
		invalidate()
	}

	/** Renders the marked and cropped region into a new bitmap at full source resolution. */
	fun renderOutput(): Bitmap {
		val bmp = checkNotNull(bitmap) { "No bitmap to render" }
		val rect = RectF(cropRect)
		val output = Bitmap.createBitmap(rect.width().toInt().coerceAtLeast(1), rect.height().toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
		val canvas = Canvas(output)
		canvas.translate(-rect.left, -rect.top)
		canvas.drawBitmap(bmp, 0f, 0f, null)
		for (stroke in strokes) {
			strokePaint.color = stroke.color
			strokePaint.strokeWidth = stroke.width
			val path = Path()
			val points = stroke.points
			if (points.isNotEmpty()) {
				path.moveTo(points[0].x, points[0].y)
				for (i in 1 until points.size) {
					path.lineTo(points[i].x, points[i].y)
				}
				if (points.size == 1) {
					// a single tap makes a dot
					path.lineTo(points[0].x + 0.1f, points[0].y + 0.1f)
				}
			}
			canvas.drawPath(path, strokePaint)
		}
		return output
	}

	override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
		super.onSizeChanged(w, h, oldw, oldh)
		updateMatrix()
	}

	private fun updateMatrix() {
		val bmp = bitmap ?: return
		if (width == 0 || height == 0) {
			return
		}
		val scale = min(width / bmp.width.toFloat(), height / bmp.height.toFloat())
		viewMatrix.reset()
		viewMatrix.postScale(scale, scale)
		val dx = (width - bmp.width * scale) / 2f
		val dy = (height - bmp.height * scale) / 2f
		viewMatrix.postTranslate(dx, dy)
		viewMatrix.invert(inverseMatrix)
		bitmapRect = RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
		viewMatrix.mapRect(bitmapRect)
	}

	override fun onDraw(canvas: Canvas) {
		super.onDraw(canvas)
		val bmp = bitmap ?: return
		canvas.drawColor(Color.BLACK)
		canvas.drawBitmap(bmp, viewMatrix, null)
		// dim everything outside the crop rectangle
		val save = canvas.save()
		canvas.clipRect(bitmapRect)
		canvas.drawRect(bitmapRect, dimPaint)
		canvas.restoreToCount(save)
		val cropInView = RectF(cropRect)
		viewMatrix.mapRect(cropInView)
		canvas.drawRect(bitmapRect, dimPaint)
		canvas.save()
		canvas.clipRect(cropInView)
		canvas.drawBitmap(bmp, viewMatrix, null)
		canvas.restore()
		canvas.drawRect(cropInView, rectPaint)
		drawHandle(canvas, cropInView.left, cropInView.top)
		drawHandle(canvas, cropInView.right, cropInView.top)
		drawHandle(canvas, cropInView.left, cropInView.bottom)
		drawHandle(canvas, cropInView.right, cropInView.bottom)
		// strokes
		for (stroke in strokes) {
			drawStroke(canvas, stroke)
		}
		activeStroke?.let { drawStroke(canvas, it) }
	}

	private fun drawStroke(canvas: Canvas, stroke: Stroke) {
		if (stroke.points.isEmpty()) {
			return
		}
		strokePaint.color = stroke.color
		strokePaint.strokeWidth = stroke.width * viewMatrix.mapRadius(1f)
		val path = Path()
		val first = toView(stroke.points[0])
		path.moveTo(first.x, first.y)
		for (i in 1 until stroke.points.size) {
			val p = toView(stroke.points[i])
			path.lineTo(p.x, p.y)
		}
		if (stroke.points.size == 1) {
			path.lineTo(first.x + 0.1f, first.y + 0.1f)
		}
		canvas.drawPath(path, strokePaint)
	}

	private fun drawHandle(canvas: Canvas, x: Float, y: Float) {
		val r = 14f
		canvas.drawCircle(x, y, r, handlePaint)
		rectPaint.strokeWidth = 2f
		canvas.drawCircle(x, y, r, rectPaint)
	}

	private fun toView(p: Point): android.graphics.PointF {
		val out = floatArrayOf(p.x, p.y)
		viewMatrix.mapPoints(out)
		return android.graphics.PointF(out[0], out[1])
	}

	private fun toImage(x: Float, y: Float): Point {
		val out = floatArrayOf(x, y)
		inverseMatrix.mapPoints(out)
		return Point(out[0], out[1])
	}

	override fun onTouchEvent(event: MotionEvent): Boolean {
		val bmp = bitmap ?: return false
		when (mode) {
			Mode.CROP -> {
				when (event.actionMasked) {
					MotionEvent.ACTION_DOWN -> {
						val image = toImage(event.x, event.y)
						dragHandle = hitHandle(event.x, event.y) ?: if (cropRect.contains(image.x, image.y)) Handle.MOVE else null
						if (dragHandle != null) {
							dragStart = image
							dragRectStart = RectF(cropRect)
							parent?.requestDisallowInterceptTouchEvent(true)
							return true
						}
						return false
					}

					MotionEvent.ACTION_MOVE -> {
						val handle = dragHandle ?: return false
						val image = toImage(event.x, event.y)
						val dx = image.x - dragStart.x
						val dy = image.y - dragStart.y
						val r = dragRectStart
						val left = r.left + dx
						val top = r.top + dy
						val right = r.right + dx
						val bottom = r.bottom + dy
						when (handle) {
							Handle.MOVE -> cropRect.set(
								left.coerceIn(0f, bmp.width - r.width()),
								top.coerceIn(0f, bmp.height - r.height()),
								right.coerceIn(r.width(), bmp.width.toFloat()),
								bottom.coerceIn(r.height(), bmp.height.toFloat()),
							)

							Handle.TOP_LEFT -> cropRect.set(
								min(image.x, cropRect.right - MIN_CROP),
								min(image.y, cropRect.bottom - MIN_CROP),
								cropRect.right,
								cropRect.bottom,
							)

							Handle.TOP_RIGHT -> cropRect.set(
								cropRect.left,
								min(image.y, cropRect.bottom - MIN_CROP),
								max(image.x, cropRect.left + MIN_CROP),
								cropRect.bottom,
							)

							Handle.BOTTOM_LEFT -> cropRect.set(
								min(image.x, cropRect.right - MIN_CROP),
								cropRect.top,
								cropRect.right,
								max(image.y, cropRect.top + MIN_CROP),
							)

							Handle.BOTTOM_RIGHT -> cropRect.set(
								cropRect.left,
								cropRect.top,
								max(image.x, cropRect.left + MIN_CROP),
								max(image.y, cropRect.top + MIN_CROP),
							)
						}
						cropRect.intersect(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
						invalidate()
						return true
					}

					MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
						dragHandle = null
						return true
					}
				}
			}

			Mode.DRAW -> {
				when (event.actionMasked) {
					MotionEvent.ACTION_DOWN -> {
						val image = toImage(event.x, event.y)
						if (!cropRect.contains(image.x, image.y)) {
							return false
						}
						activeStroke = Stroke(strokeColor, strokeWidth, mutableListOf(image))
						parent?.requestDisallowInterceptTouchEvent(true)
						invalidate()
						return true
					}

					MotionEvent.ACTION_MOVE -> {
						val stroke = activeStroke ?: return false
						val image = toImage(event.x, event.y)
						val last = stroke.points.lastOrNull()
						if (last == null || (image.x - last.x) * (image.x - last.x) + (image.y - last.y) * (image.y - last.y) > 4f) {
							stroke.points.add(image)
							invalidate()
						}
						return true
					}

					MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
						activeStroke?.let { stroke ->
							if (stroke.points.isNotEmpty()) {
								strokes.add(stroke)
							}
						}
						activeStroke = null
						invalidate()
						return true
					}
				}
			}

			Mode.PICK_COLOR -> {
				if (event.actionMasked == MotionEvent.ACTION_DOWN) {
					val image = toImage(event.x, event.y)
					if (image.x >= 0 && image.y >= 0 && image.x < bmp.width && image.y < bmp.height) {
						val pixel = bmp.getPixel(image.x.toInt(), image.y.toInt())
						strokeColor = if (android.graphics.Color.alpha(pixel) == 0) Color.WHITE else pixel
						onColorPicked?.invoke(strokeColor)
					}
					mode = Mode.DRAW
					return true
				}
			}
		}
		return super.onTouchEvent(event)
	}

	private fun hitHandle(x: Float, y: Float): Handle? {
		val cropInView = RectF(cropRect)
		viewMatrix.mapRect(cropInView)
		val touch = 48f
		return when {
			max(x - cropInView.left, cropInView.left - x) <= touch && max(y - cropInView.top, cropInView.top - y) <= touch -> Handle.TOP_LEFT
			max(x - cropInView.right, cropInView.right - x) <= touch && max(y - cropInView.top, cropInView.top - y) <= touch -> Handle.TOP_RIGHT
			max(x - cropInView.left, cropInView.left - x) <= touch && max(y - cropInView.bottom, cropInView.bottom - y) <= touch -> Handle.BOTTOM_LEFT
			max(x - cropInView.right, cropInView.right - x) <= touch && max(y - cropInView.bottom, cropInView.bottom - y) <= touch -> Handle.BOTTOM_RIGHT
			else -> null
		}
	}

	private companion object {
		const val MIN_CROP = 64f
	}
}
