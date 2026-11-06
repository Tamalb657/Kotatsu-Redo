package org.koitharu.kotatsu.local.data.output

import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Builds a single EPUB 3 file (with an EPUB 2 fallback TOC) from the buffered page archive.
 *
 * Original image bytes are preserved when the format is widely supported (JPEG, PNG, WebP, GIF);
 * exotic formats are transcoded to JPEG. One XHTML document per chapter, one image per page.
 */
object EpubBuilder {

	private const val MIMETYPE = "application/epub+zip"
	private const val MEDIA_TYPE_CSS = "text/css"
	private const val MEDIA_TYPE_XHTML = "application/xhtml+xml"
	private const val MEDIA_TYPE_NCX = "application/x-dtbncx+xml"

	fun build(
		archive: ZipFile,
		chapters: List<ArchiveChapter>,
		coverEntryName: String?,
		target: File,
		title: String,
		author: String?,
	) {
		val partFile = File(target.parentFile, target.name + ".part")
		val tempDir = target.parentFile
		val tempFiles = ArrayList<File>()
		try {
			ZipOutputStream(BufferedOutputStream(FileOutputStream(partFile))).use { zipOut ->
				zipOut.setLevel(Deflater.BEST_SPEED)
				writeMimetype(zipOut)
				zipOut.putEntry("META-INF/container.xml", containerXml())
				val context = EpubContext(
					title = title,
					author = author,
					docId = "urn:uuid:" + UUID.randomUUID(),
				)
				writeStyles(zipOut)
				val cover = writeCover(archive, zipOut, coverEntryName, tempDir, tempFiles)
				val chapterRefs = writeChapters(archive, zipOut, chapters, tempDir, tempFiles)
				writeNav(zipOut, chapterRefs, context)
				writeNcx(zipOut, chapterRefs, context)
				writeOpf(zipOut, chapterRefs, cover, context)
			}
			target.delete()
			if (!partFile.renameTo(target)) {
				throw IOException("Cannot save the EPUB file")
			}
		} finally {
			tempFiles.forEach { it.delete() }
		}
	}

	private fun writeMimetype(zipOut: ZipOutputStream) {
		val bytes = MIMETYPE.toByteArray(Charsets.US_ASCII)
		val entry = ZipEntry("mimetype").apply {
			method = ZipEntry.STORED
			size = bytes.size.toLong()
			compressedSize = bytes.size.toLong()
			crc = java.util.zip.CRC32().apply { update(bytes) }.value
		}
		zipOut.putNextEntry(entry)
		zipOut.write(bytes)
		zipOut.closeEntry()
	}

	private fun ZipOutputStream.putEntry(name: String, content: String) {
		putNextEntry(ZipEntry(name))
		write(content.toByteArray(Charsets.UTF_8))
		closeEntry()
	}

	private fun writeStyles(zipOut: ZipOutputStream) {
		zipOut.putEntry("OEBPS/style.css", STYLE_CSS)
	}

	private fun containerXml() = """
		<?xml version="1.0" encoding="UTF-8"?>
		<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
			<rootfiles>
				<rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
			</rootfiles>
		</container>
	""".trimIndent()

	private fun writeCover(
		archive: ZipFile,
		zipOut: ZipOutputStream,
		coverEntryName: String?,
		tempDir: File,
		tempFiles: MutableCollection<in File>,
	): CoverRef? {
		if (coverEntryName == null) {
			return null
		}
		val entry = archive.getEntry(coverEntryName) ?: return null
		val name = "images/cover" + extOf(coverEntryName)
		val extracted = File(tempDir, "kotatsu_epub_cover.tmp")
		tempFiles += extracted
		archive.getInputStream(entry).use { input ->
			extracted.outputStream().use { output ->
				input.copyTo(output)
			}
		}
		val kind = PageImage.probe(extracted)
		val targetName: String
		val mediaType: String
		if (kind == PageImage.Kind.OTHER) {
			val converted = File(tempDir, "kotatsu_epub_cover.jpg.tmp")
			tempFiles += converted
			PageImage.toJpegFile(extracted, converted)
			zipOut.writeFileEntry(converted, "OEBPS/images/cover.jpg")
			targetName = "images/cover.jpg"
			mediaType = "image/jpeg"
		} else {
			zipOut.writeFileEntry(extracted, "OEBPS/$name")
			targetName = name
			mediaType = kind.mediaType()
		}
		zipOut.putEntry("OEBPS/cover.xhtml", coverXhtml(targetName))
		return CoverRef(file = targetName, mediaType = mediaType)
	}

	private fun writeChapters(
		archive: ZipFile,
		zipOut: ZipOutputStream,
		chapters: List<ArchiveChapter>,
		tempDir: File,
		tempFiles: MutableCollection<in File>,
	): List<ChapterRef> {
		val result = ArrayList<ChapterRef>(chapters.size)
		var imageSeq = 0
		for ((chapterSeq, item) in chapters.withIndex()) {
			val docName = "text/ch%04d.xhtml".format(Locale.ROOT, chapterSeq + 1)
			val body = StringBuilder()
			val manifestImages = ArrayList<String>()
			for (page in item.pages) {
				val entry = archive.getEntry(page.entryName)
					?: throw IOException("Missing page entry ${page.entryName}")
				val extracted = File(tempDir, "kotatsu_epub_${imageSeq}_${page.entryName.hashCode()}.tmp")
				tempFiles += extracted
				archive.getInputStream(entry).use { input ->
					extracted.outputStream().use { output ->
						input.copyTo(output)
					}
				}
				val kind = PageImage.probe(extracted)
				val imageName: String
				val mediaType: String
				if (kind == PageImage.Kind.OTHER) {
					val converted = File(tempDir, "kotatsu_epub_c_${imageSeq}.tmp")
					tempFiles += converted
					PageImage.toJpegFile(extracted, converted)
					imageName = "images/p%05d.jpg".format(Locale.ROOT, imageSeq)
					mediaType = "image/jpeg"
					zipOut.writeFileEntry(converted, "OEBPS/$imageName")
				} else {
					val ext = when (kind) {
						PageImage.Kind.JPEG -> "jpg"
						PageImage.Kind.PNG -> "png"
						PageImage.Kind.WEBP -> "webp"
						else -> "gif"
					}
					imageName = "images/p%05d.$ext".format(Locale.ROOT, imageSeq)
					mediaType = kind.mediaType()
					zipOut.writeFileEntry(extracted, "OEBPS/$imageName")
				}
				imageSeq++
				manifestImages += imageName
				body.append(
					"""<div class="page"><img src="../$imageName" alt="Page ${page.pageNumber + 1}"/></div>""",
				)
			}
			if (body.isEmpty()) {
				continue
			}
			val docContent = chapterXhtml(item.chapter.name, body.toString())
			zipOut.putEntry("OEBPS/$docName", docContent)
			result += ChapterRef(
				id = "ch%04d".format(Locale.ROOT, chapterSeq + 1),
				href = docName,
				title = item.chapter.name,
				images = manifestImages,
			)
		}
		return result
	}

	private fun writeNav(
		zipOut: ZipOutputStream,
		chapters: List<ChapterRef>,
		context: EpubContext,
	) {
		val items = chapters.joinToString(separator = "\n") {
			"""<li><a href="${it.href}">${escapeXml(it.title)}</a></li>"""
		}
		zipOut.putEntry(
			"OEBPS/nav.xhtml",
			"""
			<?xml version="1.0" encoding="UTF-8"?>
			<!DOCTYPE html>
			<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
			<head><title>${escapeXml(context.title)}</title></head>
			<body>
				<nav epub:type="toc">
					<h1>${escapeXml(context.title)}</h1>
					<ol>
						$items
					</ol>
				</nav>
			</body>
			</html>
			""".trimIndent(),
		)
	}

	private fun writeNcx(
		zipOut: ZipOutputStream,
		chapters: List<ChapterRef>,
		context: EpubContext,
	) {
		val points = chapters.mapIndexed { index, ref ->
			"""
			<navPoint id="${ref.id}" playOrder="${index + 1}">
				<navLabel><text>${escapeXml(ref.title)}</text></navLabel>
				<content src="${ref.href}"/>
			</navPoint>
			""".trimIndent()
		}.joinToString("\n")
		zipOut.putEntry(
			"OEBPS/toc.ncx",
			"""
			<?xml version="1.0" encoding="UTF-8"?>
			<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
				<head>
					<meta name="dtb:uid" content="${context.docId}"/>
				</head>
				<docTitle><text>${escapeXml(context.title)}</text></docTitle>
				<navMap>
					$points
				</navMap>
			</ncx>
			""".trimIndent(),
		)
	}

	private fun writeOpf(
		zipOut: ZipOutputStream,
		chapters: List<ChapterRef>,
		cover: CoverRef?,
		context: EpubContext,
	) {
		val builder = StringBuilder()
		builder.append(
			"""
			<?xml version="1.0" encoding="UTF-8"?>
			<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="pub-id" xml:lang="en">
				<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
					<dc:title>${escapeXml(context.title)}</dc:title>
					<dc:language>en</dc:language>
					<dc:identifier id="pub-id">${context.docId}</dc:identifier>
					<meta property="dcterms:modified">${context.modified}</meta>
			""".trimIndent(),
		)
		val author = context.author?.ifBlank { null }
		if (author != null) {
			builder.append("\n\t\t\t\t\t<dc:creator>").append(escapeXml(author)).append("</dc:creator>")
		}
		builder.append("\n\t\t\t\t</metadata>\n\t\t\t\t<manifest>\n")
		builder.append("\t\t\t\t\t<item id=\"nav\" href=\"nav.xhtml\" media-type=\"$MEDIA_TYPE_XHTML\" properties=\"nav\"/>\n")
		builder.append("\t\t\t\t\t<item id=\"ncx\" href=\"toc.ncx\" media-type=\"$MEDIA_TYPE_NCX\"/>\n")
		builder.append("\t\t\t\t\t<item id=\"css\" href=\"style.css\" media-type=\"$MEDIA_TYPE_CSS\"/>\n")
		if (cover != null) {
			builder.append("\t\t\t\t\t<item id=\"cover-image\" href=\"${cover.file}\" media-type=\"${cover.mediaType}\" properties=\"cover-image\"/>\n")
			builder.append("\t\t\t\t\t<item id=\"cover-page\" href=\"cover.xhtml\" media-type=\"$MEDIA_TYPE_XHTML\"/>\n")
		}
		for (chapter in chapters) {
			builder.append("\t\t\t\t\t<item id=\"${chapter.id}\" href=\"${chapter.href}\" media-type=\"$MEDIA_TYPE_XHTML\"/>\n")
			for ((i, image) in chapter.images.withIndex()) {
				builder.append(
					"\t\t\t\t\t<item id=\"${chapter.id}_img$i\" href=\"$image\" media-type=\"${mediaTypeOf(image)}\"/>\n",
				)
			}
		}
		builder.append("\t\t\t\t</manifest>\n\t\t\t\t<spine toc=\"ncx\">\n")
		if (cover != null) {
			builder.append("\t\t\t\t\t<itemref idref=\"cover-page\" linear=\"yes\"/>\n")
		}
		for (chapter in chapters) {
			builder.append("\t\t\t\t\t<itemref idref=\"${chapter.id}\"/>\n")
		}
		builder.append("\t\t\t\t</spine>\n\t\t\t</package>")
		zipOut.putEntry("OEBPS/content.opf", builder.toString())
	}

	private fun coverXhtml(imageHref: String) = """
		<?xml version="1.0" encoding="UTF-8"?>
		<!DOCTYPE html>
		<html xmlns="http://www.w3.org/1999/xhtml">
		<head>
			<title>Cover</title>
			<style type="text/css">body { margin: 0; text-align: center; } img { max-width: 100%; max-height: 100%; }</style>
		</head>
		<body>
			<div><img src="$imageHref" alt="Cover"/></div>
		</body>
		</html>
	""".trimIndent()

	private fun chapterXhtml(chapterName: String, bodyContent: String) = """
		<?xml version="1.0" encoding="UTF-8"?>
		<!DOCTYPE html>
		<html xmlns="http://www.w3.org/1999/xhtml">
		<head>
			<title>${escapeXml(chapterName)}</title>
			<link rel="stylesheet" type="text/css" href="../style.css"/>
		</head>
		<body class="manga">
			$bodyContent
		</body>
		</html>
	""".trimIndent()

	private const val STYLE_CSS = """
		body { margin: 0; padding: 0; text-align: center; }
		div.page { margin: 0; padding: 0; page-break-after: always; break-after: page; text-align: center; }
		div.page:last-child { page-break-after: avoid; break-after: avoid; }
		img { max-width: 100%; }
	"""

	private fun escapeXml(value: String): String = buildString(value.length) {
		for (ch in value) {
			when (ch) {
				'&' -> append("&amp;")
				'<' -> append("&lt;")
				'>' -> append("&gt;")
				'"' -> append("&quot;")
				'\'' -> append("&#39;")
				else -> append(ch)
			}
		}
	}

	private fun extOf(entryName: String): String {
		val lastDot = entryName.lastIndexOf('.')
		return if (lastDot >= 0) entryName.substring(lastDot) else ".jpg"
	}

	private fun mediaTypeOf(imageName: String): String = when (imageName.substringAfterLast('.')) {
		"png" -> "image/png"
		"webp" -> "image/webp"
		"gif" -> "image/gif"
		else -> "image/jpeg"
	}

	private fun PageImage.Kind.mediaType(): String = when (this) {
		PageImage.Kind.JPEG -> "image/jpeg"
		PageImage.Kind.PNG -> "image/png"
		PageImage.Kind.WEBP -> "image/webp"
		PageImage.Kind.GIF -> "image/gif"
		PageImage.Kind.OTHER -> "image/jpeg"
	}

	private fun ZipOutputStream.writeFileEntry(file: File, entryName: String) {
		file.inputStream().buffered().use { input ->
			putNextEntry(ZipEntry(entryName))
			input.copyTo(this)
			closeEntry()
		}
	}

	private class EpubContext(
		val title: String,
		val author: String?,
		val docId: String,
	) {
		val modified: String = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).run {
			timeZone = java.util.TimeZone.getTimeZone("UTC")
			format(java.util.Date())
		}
	}

	private class ChapterRef(
		val id: String,
		val href: String,
		val title: String,
		val images: List<String>,
	)

	private class CoverRef(
		val file: String,
		val mediaType: String,
	)
}

