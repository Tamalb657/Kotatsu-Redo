package org.koitharu.kotatsu.local.data.output

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.internal.closeQuietly
import org.koitharu.kotatsu.core.image.BitmapDecoderCompat
import org.koitharu.kotatsu.core.util.MimeTypes
import org.koitharu.kotatsu.core.util.ext.MimeType
import org.koitharu.kotatsu.core.util.ext.deleteAwait
import org.koitharu.kotatsu.core.zip.ZipOutput
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * Intermediate buffer for export-only formats (PDF, EPUB).
 *
 * Pages are appended into a temporary ZIP archive as they are downloaded.
 * On [finish] the archive is finalized and passed to [build], which produces the final file.
 *
 * Unlike the CBZ outputs, an export cannot be resumed or merged with a previously
 * downloaded manga: it is always written from scratch.
 */
abstract class LocalMangaArchiveOutput(
	rootFile: File,
	protected val manga: Manga,
) : LocalMangaOutput(rootFile) {

	private val output = ZipOutput(File(rootFile.path + SUFFIX_TMP))
	private val mutex = Mutex()
	private val chapters = LinkedHashMap<MangaChapter, MutableList<ArchivePage>>()
	private var coverEntryName: String? = null

	/** Builds the final file from the buffered archive. Runs on a worker thread. */
	protected abstract fun build(
		archive: ZipFile,
		chapters: List<ArchiveChapter>,
		coverEntryName: String?,
		target: File,
	)

	override suspend fun mergeWithExisting() = mutex.withLock { }

	override suspend fun addCover(file: File, type: MimeType?) = mutex.withLock {
		val name = buildString {
			append(ENTRY_NAME_COVER)
			MimeTypes.getExtension(type)?.let { ext ->
				append('.')
				append(ext)
			}
		}
		runInterruptible(Dispatchers.IO) {
			output.put(name, file)
		}
		coverEntryName = name
	}

	override suspend fun addPage(chapter: IndexedValue<MangaChapter>, file: File, pageNumber: Int, type: MimeType?): Unit =
		mutex.withLock {
			val entryName = buildString {
				append(FILENAME_PATTERN.format(chapter.value.branch.hashCode(), chapter.index + 1, pageNumber + 1))
				MimeTypes.getExtension(type)?.let { ext ->
					append('.')
					append(ext)
				}
			}
			runInterruptible(Dispatchers.IO) {
				output.put(entryName, file)
			}
			chapters.getOrPut(chapter.value) { mutableListOf() }.add(ArchivePage(pageNumber, entryName))
			Unit
		}

	override suspend fun flushChapter(chapter: MangaChapter): Boolean = false

	override suspend fun finish(): Unit = mutex.withLock {
		if (!output.file.exists()) {
			throw IllegalStateException("No pages were added to the export")
		}
		runInterruptible(Dispatchers.IO) {
			output.use {
				output.finish()
			}
			ZipFile(output.file).use { archive ->
				build(
					archive = archive,
					chapters = chapters.map { (chapter, pages) ->
						ArchiveChapter(chapter, pages.sortedBy(ArchivePage::pageNumber))
					},
					coverEntryName = coverEntryName,
					target = rootFile,
				)
			}
			output.file.delete()
		}
	}

	override suspend fun cleanup(): Unit = mutex.withLock {
		output.file.deleteAwait()
	}

	override fun close() {
		output.close()
	}

	private companion object {

		const val ENTRY_NAME_COVER = "cover"
		const val FILENAME_PATTERN = "%08d_%04d%04d"
	}
}

class ArchivePage(
	val pageNumber: Int,
	val entryName: String,
)

class ArchiveChapter(
	val chapter: MangaChapter,
	val pages: List<ArchivePage>,
)

/**
 * Extracts a single page image from the intermediate archive and encodes it as a JPEG file,
 * so that it can be embedded into a PDF.
 */
internal object PageImage {

	private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
	private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
	private val WEBP_MAGIC = byteArrayOf(0x52, 0x49, 0x50, 0x46) // "RIFF"
	private val GIF_MAGIC = byteArrayOf(0x47, 0x49, 0x46, 0x38) // "GIF8"

	enum class Kind {
		JPEG, PNG, WEBP, GIF, OTHER,
	}

	fun probe(file: File): Kind {
		file.inputStream().use { input ->
			val header = ByteArray(4)
			if (readFully(input, header) < header.size) {
				return Kind.OTHER
			}
			return when {
				header.startsWith(JPEG_MAGIC) -> Kind.JPEG
				header.startsWith(PNG_MAGIC) -> Kind.PNG
				header.startsWith(WEBP_MAGIC) -> Kind.WEBP
				header.startsWith(GIF_MAGIC) -> Kind.GIF
				else -> Kind.OTHER
			}
		}
	}

	/** Decodes any supported image format and writes it back as a JPEG file (alpha flattened onto white). */
	fun toJpegFile(source: File, target: File, quality: Int = 88) {
		val bitmap = BitmapDecoderCompat.decode(source)
		val flattened = flattenOnWhite(bitmap)
		try {
			FileOutputStream(target).use { out ->
				flattened.compress(Bitmap.CompressFormat.JPEG, quality, out)
			}
		} finally {
			if (flattened !== bitmap) {
				bitmap.recycle()
			}
			flattened.recycle()
		}
	}

	private fun flattenOnWhite(bitmap: Bitmap): Bitmap {
		if (!bitmap.hasAlpha()) {
			return bitmap
		}
		val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
		result.eraseColor(Color.WHITE)
		Canvas(result).drawBitmap(bitmap, 0f, 0f, null)
		return result
	}

	private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
		if (size < prefix.size) {
			return false
		}
		for (i in prefix.indices) {
			if (this[i] != prefix[i]) {
				return false
			}
		}
		return true
	}

	private fun readFully(input: InputStream, buffer: ByteArray): Int {
		var total = 0
		while (total < buffer.size) {
			val read = input.read(buffer, total, buffer.size - total)
			if (read < 0) {
				return total
			}
			total += read
		}
		return total
	}
}