package org.koitharu.kotatsu.local.data.output

import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.ZipFile

/**
 * Builds a single PDF file from the buffered page archive.
 *
 * JPEG pages are embedded byte-identical (DCTDecode passthrough); other formats are
 * decoded and embedded losslessly (FlateDecode, DeviceRGB). One PDF page per manga page,
 * sized to the image dimensions.
 */
object PdfBuilder {

	private const val MAX_PAGE_UNITS = 14400 // max MediaBox size per PDF spec

	fun build(
		archive: ZipFile,
		chapters: List<ArchiveChapter>,
		coverEntryName: String?,
		target: File,
		title: String,
	) {
		val partFile = File(target.parentFile, target.name + ".part")
		val tempDir = target.parentFile
		val tempFiles = ArrayList<File>()
		try {
			FileOutputStream(partFile).buffered().use { out ->
				val writer = PdfStreamWriter(out)
				writer.writeHeader()
				val images = ArrayList<PreparedImage>()
				val pageList = buildPageList(archive, chapters, coverEntryName)
				for (page in pageList) {
					images += prepareImage(archive, page, tempDir, tempFiles)
				}
				writer.writeDocument(images, title)
			}
			target.delete()
			if (!partFile.renameTo(target)) {
				throw IOException("Cannot save the PDF file")
			}
		} finally {
			tempFiles.forEach { it.delete() }
		}
	}

	private fun buildPageList(
		archive: ZipFile,
		chapters: List<ArchiveChapter>,
		coverEntryName: String?,
	): List<PreparedImage.Source> {
		val result = ArrayList<PreparedImage.Source>()
		coverEntryName?.let { entryName ->
			if (archive.getEntry(entryName) != null) {
				result += PreparedImage.Source(entryName, isCover = true)
			}
		}
		for (chapter in chapters) {
			for (page in chapter.pages) {
				result += PreparedImage.Source(page.entryName, isCover = false)
			}
		}
		return result
	}

	private fun prepareImage(
		archive: ZipFile,
		source: PreparedImage.Source,
		tempDir: File,
		tempFiles: MutableCollection<in File>,
	): PreparedImage {
		val entry = archive.getEntry(source.entryName)
			?: throw IOException("Missing page entry ${source.entryName}")
		val extracted = File(tempDir, "kotatsu_export_${source.entryName.hashCode()}_${tempFiles.size}.tmp")
		tempFiles += extracted
		archive.getInputStream(entry).use { input ->
			extracted.outputStream().use { output ->
				input.copyTo(output)
			}
		}
		return when (PageImage.probe(extracted)) {
			PageImage.Kind.JPEG -> {
				val size = jpegSize(extracted)
					?: decodeSize(extracted)
				PreparedImage(
					file = extracted,
					width = size.width,
					height = size.height,
					grayscale = size.components == 1,
				)
			}

			else -> {
				val jpegFile = File(tempDir, "kotatsu_export_conv_${tempFiles.size}.tmp")
				tempFiles += jpegFile
				PageImage.toJpegFile(extracted, jpegFile)
				val size = jpegSize(jpegFile) ?: decodeSize(jpegFile)
				PreparedImage(
					file = jpegFile,
					width = size.width,
					height = size.height,
					grayscale = false,
				)
			}
		}
	}

	private fun decodeSize(file: File): ImageSize {
		val options = BitmapFactory.Options().apply {
			inJustDecodeBounds = true
		}
		BitmapFactory.decodeFile(file.path, options)
		check(options.outWidth > 0 && options.outHeight > 0) { "Unsupported page image format" }
		return ImageSize(options.outWidth, options.outHeight)
	}

	private fun jpegSize(file: File): ImageSize? {
		file.inputStream().buffered().use { input ->
			val header = ByteArray(2)
			if (readFully(input, header) < 2 || header[0] != 0xFF.toByte() || header[1] != 0xD8.toByte()) {
				return null
			}
			while (true) {
				val marker = input.readJpegMarker() ?: return null
				when (marker) {
					in 0xC0..0xCF -> {
						if (marker == 0xC4 || marker == 0xC8 || marker == 0xCC) {
							// DHT, JPG, DAC: not a frame header
							val length = input.readInt16()
							input.skipFully(length - 2)
						} else {
							val length = input.readInt16()
							input.skipFully(1) // precision
							val height = input.readInt16()
							val width = input.readInt16()
							val components = input.read()
							input.skipFully(length - 8)
							return ImageSize(width, height, components)
						}
					}

					0xD8, 0x01 -> continue // SOI, TEM: no payload
					0xD9 -> return null // EOI

					else -> {
						val length = input.readInt16()
						if (length < 2) {
							return null
						}
						input.skipFully(length - 2)
					}
				}
			}
		}
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

	private fun InputStream.readJpegMarker(): Int? {
		while (true) {
			var b = read()
			if (b < 0) {
				return null
			}
			if (b != 0xFF) {
				continue // skip garbage before marker
			}
			do {
				b = read()
			} while (b == 0xFF)
			if (b < 0) {
				return null
			}
			return b
		}
	}

	private fun InputStream.readInt16(): Int {
		val hi = read()
		val lo = read()
		if (hi < 0 || lo < 0) {
			throw IOException("Unexpected end of JPEG data")
		}
		return (hi shl 8) or lo
	}

	private fun InputStream.skipFully(count: Int) {
		var remaining = count.toLong()
		while (remaining > 0) {
			val skipped = skip(remaining)
			if (skipped <= 0) {
				if (read() < 0) {
					return
				}
				remaining--
			} else {
				remaining -= skipped
			}
		}
	}

	private class PreparedImage(
		val file: File,
		val width: Int,
		val height: Int,
		val grayscale: Boolean,
	) {

		class Source(
			val entryName: String,
			val isCover: Boolean,
		)
	}

	private class ImageSize(val width: Int, val height: Int, val components: Int = 3)

	private class PdfStreamWriter(private val out: OutputStream) {

		private val offsets = ArrayList<Long>()
		private var pos = 0L

		fun writeHeader() {
			writeRaw("%PDF-1.4\n")
			writeRawBytes(byteArrayOf(0x25, 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte()))
		}

		fun writeDocument(images: List<PreparedImage>, title: String) {
			val count = images.size
			check(count > 0) { "Cannot build a PDF without pages" }
			// object 1: catalog, object 2: pages root
			writeObject(1, "<< /Type /Catalog /Pages 2 0 R >>")
			val kids = buildString {
				append("<< /Type /Pages /Count ")
				append(count)
				append(" /Kids [")
				for (i in 0 until count) {
					if (i > 0) {
						append(' ')
					}
					append(3 + 3 * i + 2)
					append(" 0 R")
				}
				append("] >>")
			}
			writeObject(2, kids)
			for (i in 0 until count) {
				val image = images[i]
				val imgId = 3 + 3 * i
				val contentId = imgId + 1
				val pageId = imgId + 2
				// image XObject (JPEG passthrough)
				writeObjectHeader(imgId)
				val fileLength = image.file.length()
				val colorSpace = if (image.grayscale) "/DeviceGray" else "/DeviceRGB"
				writeRaw("<< /Type /XObject /Subtype /Image /Width ${image.width} /Height ${image.height}")
				writeRaw(" /ColorSpace $colorSpace /BitsPerComponent 8 /Filter /DCTDecode /Length $fileLength >>")
				writeRaw("\nstream\n")
				writeFileBytes(image.file)
				writeRaw("\nendstream")
				writeObjectFooter()
				// content stream: draw the image filling the page
				val scale = minOf(1f, MAX_PAGE_UNITS.toFloat() / maxOf(image.width, image.height))
				val w = image.width * scale
				val h = image.height * scale
				val content = "q\n${w} 0 0 ${h} 0 0 cm\n/Im0 Do\nQ"
				val contentBytes = content.toByteArray(Charsets.ISO_8859_1)
				writeObjectHeader(contentId)
				writeRaw("<< /Length ${contentBytes.size} >>")
				writeRaw("\nstream\n")
				writeRawBytes(contentBytes)
				writeRaw("\nendstream")
				writeObjectFooter()
				// page object
				writeObject(
					pageId,
					"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $w $h]" +
						" /Resources << /XObject << /Im0 ${imgId} 0 R >> >>" +
						" /Contents ${contentId} 0 R >>",
				)
			}
			// Info dictionary (title as UTF-16BE hex string to survive non-ASCII titles)
			val infoId = 3 + 3 * count
			val titleHex = buildString {
				append("<FEFF")
				for (b in title.toByteArray(StandardCharsets.UTF_16BE)) {
					append("%02X".format(Locale.ROOT, b))
				}
				append('>')
			}
			writeObject(infoId, "<< /Title $titleHex /Producer (Kotatsu) >>")
			writeXrefAndTrailer(infoId + 1, infoId)
		}

		private fun writeXrefAndTrailer(size: Int, infoId: Int) {
			val xrefOffset = pos
			writeRaw("xref\n")
			writeRaw("0 ${size}\n")
			// each entry must be exactly 20 bytes, including a 2-byte end-of-line marker
			writeRaw("0000000000 65535 f \n")
			for (offset in offsets) {
				writeRaw("%010d %05d n \n".format(Locale.ROOT, offset, 0))
			}
			writeRaw("trailer\n<< /Size ${size} /Root 1 0 R /Info ${infoId} 0 R >>\n")
			writeRaw("startxref\n${xrefOffset}\n%%EOF\n")
		}

		private fun writeObject(id: Int, dict: String) {
			writeObjectHeader(id)
			writeRaw(dict)
			writeObjectFooter()
		}

		private fun writeObjectHeader(id: Int) {
			offsets.add(pos)
			writeRaw("${id} 0 obj\n")
		}

		private fun writeObjectFooter() {
			writeRaw("\nendobj\n")
		}

		private fun writeRaw(s: String) {
			val b = s.toByteArray(Charsets.ISO_8859_1)
			out.write(b)
			pos += b.size
		}

		private fun writeRawBytes(b: ByteArray) {
			out.write(b)
			pos += b.size
		}

		private fun writeFileBytes(file: File) {
			file.inputStream().buffered().use { input ->
				val buffer = ByteArray(8192)
				while (true) {
					val read = input.read(buffer)
					if (read < 0) {
						break
					}
					out.write(buffer, 0, read)
					pos += read
				}
			}
		}
	}
}
