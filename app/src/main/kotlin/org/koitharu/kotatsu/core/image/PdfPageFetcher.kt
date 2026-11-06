package org.koitharu.kotatsu.core.image

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.fetch.Fetcher
import coil3.fetch.FetchResult
import coil3.fetch.SourceFetchResult
import coil3.decode.ImageSource
import coil3.request.Options
import coil3.toAndroidUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.File
import java.io.FileOutputStream
import coil3.Uri as CoilUri

/**
 * Renders pages of a PDF file for the image loader.
 * Handles URIs of the form `pdf:/absolute/path/to/file.pdf#<page index>`.
 */
class PdfPageFetcher(
	private val uri: Uri,
	private val options: Options,
) : Fetcher {

	override suspend fun fetch(): FetchResult = runInterruptible(Dispatchers.IO) {
		val file = File(requireNotNull(uri.path) { "Pdf uri has no path: $uri" })
		val pageIndex = uri.fragment?.toIntOrNull()?.coerceAtLeast(0) ?: 0
		val bitmap = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
			PdfRenderer(pfd).use { renderer ->
				renderer.openPage(pageIndex).use { page ->
					// render at up to ~3x the native 72dpi size, capped to keep memory sane
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
		try {
			// encode into a small cache file that gets reused (and overwritten) on every load
			val cacheFile = File(options.context.cacheDir, pdfCacheName(file, pageIndex))
			FileOutputStream(cacheFile).use { out ->
				bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
			}
			SourceFetchResult(
				source = ImageSource(cacheFile.toOkioPath(), FileSystem.SYSTEM),
				mimeType = "image/jpeg",
				dataSource = DataSource.DISK,
			)
		} finally {
			bitmap.recycle()
		}
	}

	private fun pdfCacheName(file: File, pageIndex: Int): String {
		val key = file.longHashCode()
		return "kotatsu_pdf_%s_%d.jpg".format(java.util.Locale.ROOT, java.lang.Long.toHexString(key), pageIndex)
	}

	private fun File.longHashCode(): Long = absolutePath.hashCode().toLong() * 31 + length()

	class Factory : Fetcher.Factory<CoilUri> {

		override fun create(
			data: CoilUri,
			options: Options,
		imageLoader: ImageLoader,
		): Fetcher? {
			val androidUri = data.toAndroidUri()
			return if (androidUri.scheme == "pdf") {
				PdfPageFetcher(androidUri, options)
			} else {
				null
			}
		}
	}
}
