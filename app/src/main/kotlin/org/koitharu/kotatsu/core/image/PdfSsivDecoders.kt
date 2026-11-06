package org.koitharu.kotatsu.core.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.davemorrissey.labs.subscaleview.decoder.DecoderFactory
import com.davemorrissey.labs.subscaleview.decoder.ImageDecoder
import com.davemorrissey.labs.subscaleview.decoder.ImageRegionDecoder
import java.io.File

private const val PDF_SCHEME = "pdf"

/**
 * Renders a whole PDF page into a bitmap for the zoomable reader view.
 * URIs of the form `pdf:/path/file.pdf#<page index>`.
 */
class PdfBitmapDecoder(
	private val delegate: ImageDecoder,
) : ImageDecoder {

	override fun decode(context: Context, uri: Uri, sampleSize: Int): Bitmap = if (uri.scheme == PDF_SCHEME) {
		val full = PdfPageRenderer.renderToBitmap(uri)
		try {
			scaleForSample(full, sampleSize)
		} finally {
			full.recycle()
		}
	} else {
		delegate.decode(context, uri, sampleSize)
	}

	private fun scaleForSample(bitmap: Bitmap, sampleSize: Int): Bitmap =
		if (sampleSize <= 1) {
			bitmap
		} else {
			Bitmap.createScaledBitmap(
				bitmap,
				(bitmap.width / sampleSize.toFloat()).toInt().coerceAtLeast(1),
				(bitmap.height / sampleSize.toFloat()).toInt().coerceAtLeast(1),
				true,
			)
		}

	class Factory(
		private val delegate: DecoderFactory<out ImageDecoder>,
	) : DecoderFactory<ImageDecoder> {

		override val bitmapConfig: Bitmap.Config?
			get() = delegate.bitmapConfig

		override fun make(): ImageDecoder = PdfBitmapDecoder(delegate.make())
	}
}

/**
 * Region decoder for the zoomable reader view: renders the PDF page once and answers
 * tile requests by cropping it. Other schemes are delegated.
 */
class PdfRegionDecoder(
	private val delegate: ImageRegionDecoder,
) : ImageRegionDecoder {

	private var bitmap: Bitmap? = null

	override fun init(context: Context, uri: Uri): android.graphics.Point {
		if (uri.scheme != PDF_SCHEME) {
			return delegate.init(context, uri)
		}
		bitmap?.recycle()
		bitmap = PdfPageRenderer.renderToBitmap(uri)
		return android.graphics.Point(checkNotNull(bitmap).width, checkNotNull(bitmap).height)
	}

	override val isReady: Boolean
		get() = bitmap != null

	override fun decodeRegion(sRect: Rect, sampleSize: Int): Bitmap {
		val base = checkNotNull(bitmap) { "PdfRegionDecoder is not initialized" }
		val left = (sRect.left / sampleSize.toFloat()).toInt().coerceIn(0, base.width - 1)
		val top = (sRect.top / sampleSize.toFloat()).toInt().coerceIn(0, base.height - 1)
		val width = ((sRect.width() / sampleSize.toFloat()).toInt()).coerceAtMost(base.width - left)
		val height = ((sRect.height() / sampleSize.toFloat()).toInt()).coerceAtMost(base.height - top)
		return Bitmap.createBitmap(base, left, top, width.coerceAtLeast(1), height.coerceAtLeast(1))
	}

	override fun recycle() {
		bitmap?.recycle()
		bitmap = null
	}

	class Factory(
		private val delegate: DecoderFactory<out ImageRegionDecoder>,
	) : DecoderFactory<ImageRegionDecoder> {

		override val bitmapConfig: Bitmap.Config?
			get() = delegate.bitmapConfig

		override fun make(): ImageRegionDecoder = PdfRegionDecoder(delegate.make())
	}
}

internal object PdfPageRenderer {

	fun renderToBitmap(uri: Uri): Bitmap {
		val file = java.io.File(java.util.Objects.requireNonNull(uri.path))
		val pageIndex = uri.fragment?.toIntOrNull()?.coerceAtLeast(0) ?: 0
		return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
			PdfRenderer(pfd).use { renderer ->
				renderer.openPage(pageIndex.coerceAtMost(renderer.pageCount - 1)).use { page ->
					val scale = minOf(3f, 2048f / maxOf(page.width, page.height).coerceAtLeast(1)).coerceAtLeast(1f)
					val width = (page.width * scale).toInt().coerceAtLeast(1)
					val height = (page.height * scale).toInt().coerceAtLeast(1)
					Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
						bitmap.eraseColor(Color.WHITE)
						page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
					}
				}
			}
		}
	}
}
