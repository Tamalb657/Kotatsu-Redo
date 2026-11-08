package org.koitharu.kotatsu.local.data.input

import android.util.Xml
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.util.MimeTypes
import org.koitharu.kotatsu.core.util.ext.isImage
import org.koitharu.kotatsu.core.util.ext.toZipUri
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.util.longHashCode
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.parsers.util.toTitleCase
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipFile

/**
 * Reads manga from an EPUB file: the OPF package document defines the chapter order (spine),
 * every spine document containing images is a chapter, its images are the pages.
 * Page images are loaded through the regular [CbzFetcher] by their `file+zip://` URIs.
 */
class EpubMangaParser(private val file: File) {

	suspend fun getManga(withDetails: Boolean): LocalManga = runInterruptible(Dispatchers.IO) {
		parse(withDetails)
	}

	suspend fun getMangaInfo(): Manga? = runInterruptible(Dispatchers.IO) {
		parse(withDetails = false).manga
	}

	suspend fun getPages(chapter: MangaChapter): List<MangaPage> = runInterruptible(Dispatchers.IO) {
		val docPath = chapter.url.toUri().fragment.orEmpty()
		ZipFile(file).use { zip ->
			val entries = if (docPath.isEmpty()) {
				// fallback chapter: every image in the archive
				zip.entries().asSequence()
					.filter { !it.isDirectory }
					.map { it.name }
					.filter { MimeTypes.getMimeTypeFromExtension(it.substringAfterLast('.', ""))?.isImage == true }
					.sorted()
					.toList()
			} else {
				val docDir = docPath.substringBeforeLast('/', "")
				readDocumentPages(zip, docPath, docDir)
			}
			entries.map { entryName ->
				val pageUrl = file.toZipUri(entryName).toString()
				MangaPage(
					id = pageUrl.longHashCode(),
					url = pageUrl,
					preview = null,
					source = LocalMangaSource,
				)
			}
		}
	}

	private fun parse(withDetails: Boolean): LocalManga {
		val info = ZipFile(file).use { zip ->
			readPackage(zip)
		}
		val title = info.title?.ifBlank { null } ?: file.name.removeSuffix(".epub").replace('_', ' ').toTitleCase()
		val coverUrl = info.coverEntry ?: info.chapters.firstOrNull()?.pages?.firstOrNull()
		val manga = Manga(
			id = file.absolutePath.longHashCode(),
			title = title,
			url = file.toUri().toString(),
			publicUrl = file.toUri().toString(),
			source = LocalMangaSource,
			coverUrl = coverUrl?.let { file.toZipUri(it).toString() },
			chapters = if (withDetails) {
				info.chapters.mapIndexed { i, doc ->
					MangaChapter(
						// stable across launches: derived from the position and path of the spine
						// document, not from the object identity
						id = "$i:${doc.path}".longHashCode(),
						title = doc.title ?: "Chapter ${i + 1}",
						number = (i + 1).toFloat(),
						volume = 0,
						source = LocalMangaSource,
						uploadDate = 0L,
						url = file.toUri().buildUpon().fragment(doc.path).build().toString(),
						scanlator = null,
						branch = null,
					)
				}
			} else {
				null
			},
			altTitles = emptySet(),
			rating = -1f,
			contentRating = null,
			tags = emptySet(),
			state = null,
			authors = info.author?.let { setOf(it) } ?: emptySet(),
			largeCoverUrl = null,
			description = null,
		)
		return LocalManga(manga, file)
	}

	private fun readPackage(zip: ZipFile): PackageInfo {
		val opfPath = findOpfPath(zip)
		return if (opfPath != null) {
			parseOpf(zip, opfPath)
		} else {
			fallbackPackage(zip) ?: PackageInfo(title = null, author = null, coverEntry = null, chapters = emptyList())
		}
	}

	private fun findOpfPath(zip: ZipFile): String? = runCatchingCancellable {
		zip.getInputStream(zip.getEntry("META-INF/container.xml")).use { input ->
			val parser = Xml.newPullParser()
			parser.setInput(input, null)
			while (parser.next() != XmlPullParser.END_DOCUMENT) {
				if (parser.eventType == XmlPullParser.START_TAG && parser.name == "rootfile") {
					return@runCatchingCancellable parser.getAttributeValue(null, "full-path")
				}
			}
			null
		}
	}.getOrNull()

	private fun parseOpf(zip: ZipFile, opfPath: String): PackageInfo {
		val opfDir = opfPath.substringBeforeLast('/', "")
		return ZipFile(file).use { zf ->
			val entry = zf.getEntry(opfPath)
				?: throw IllegalStateException("OPF $opfPath not found")
			val items = LinkedHashMap<String, OpfItem>()
			val spine = ArrayList<String>()
			var title: String? = null
			var author: String? = null
			var coverItemHref: String? = null
			var coverMetaId: String? = null
			zf.getInputStream(entry).use { input ->
				val parser = Xml.newPullParser()
				parser.setInput(input, null)
				var inTitle = false
				var inCreator = false
				while (parser.next() != XmlPullParser.END_DOCUMENT) {
					when (parser.eventType) {
						XmlPullParser.START_TAG -> when (parser.name) {
							"title" -> inTitle = true
							"creator" -> inCreator = true
							"item" -> {
								val id = parser.getAttributeValue(null, "id")
								val href = parser.getAttributeValue(null, "href")
								if (id != null && href != null) {
									items[id] = OpfItem(
										href = resolveRelative(opfDir, href),
										mediaType = parser.getAttributeValue(null, "media-type").orEmpty(),
										properties = parser.getAttributeValue(null, "properties").orEmpty(),
									)
								}
							}

							"itemref" -> {
								val idref = parser.getAttributeValue(null, "idref")
								if (idref != null) {
									spine += idref
								}
							}

							"meta" -> {
								if (parser.getAttributeValue(null, "name") == "cover") {
									coverMetaId = parser.getAttributeValue(null, "content")
								}
							}
						}

						XmlPullParser.TEXT -> when {
							inTitle -> title = parser.text?.trim()?.takeUnless { it.isEmpty() }
							inCreator -> author = parser.text?.trim()?.takeUnless { it.isEmpty() }
						}

						XmlPullParser.END_TAG -> when (parser.name) {
							"title" -> inTitle = false
							"creator" -> inCreator = false
						}
					}
				}
			}
			val coverHref = coverItemHref
				?: items[coverMetaId]?.href
				?: items.values.firstOrNull { "cover-image" in it.properties }?.href
			PackageInfo(
				title = title,
				author = author,
				coverEntry = coverHref?.takeIf { zf.getEntry(it) != null },
				chapters = buildSpineChapters(zf, items, spine, opfDir),
			)
		}
	}

	private fun buildSpineChapters(
		zip: ZipFile,
		items: Map<String, OpfItem>,
		spine: List<String>,
		opfDir: String,
	): List<EpubChapter> {
		val chapters = ArrayList<EpubChapter>()
		var index = 0
		for (idref in spine) {
			val item = items[idref] ?: continue
			if (item.mediaType != MEDIA_TYPE_XHTML) {
				continue
			}
			if (zip.getEntry(item.href) == null) {
				continue
			}
			val pages = readDocumentPages(zip, item.href, item.href.substringBeforeLast('/', ""))
			if (pages.isEmpty()) {
				continue
			}
			chapters += EpubChapter(
				path = item.href,
				title = readDocumentTitle(zip, item.href) ?: "Chapter ${index + 1}",
				pages = pages,
			)
			index++
		}
		if (chapters.isEmpty()) {
			fallbackPackage(zip)?.chapters?.let { fallback ->
				chapters += fallback
			}
		}
		return chapters
	}

	private fun readDocumentPages(zip: ZipFile, docPath: String, docDir: String): List<String> {
		val entry = zip.getEntry(docPath) ?: return emptyList()
		val text = zip.getInputStream(entry).use { input ->
			input.readBytes().toString(Charsets.UTF_8)
		}
		val result = ArrayList<String>()
		for (tag in IMG_TAG_REGEX.findAll(text)) {
			val src = SRC_ATTR_REGEX.find(tag.value)?.groupValues?.get(1) ?: continue
			val resolved = resolveRelative(docDir, src)
			if (zip.getEntry(resolved) != null) {
				result += resolved
			}
		}
		return result
	}

	private fun readDocumentTitle(zip: ZipFile, docPath: String): String? {
		val entry = zip.getEntry(docPath) ?: return null
		val text = zip.getInputStream(entry).use { input ->
			input.readBytes().toString(Charsets.UTF_8)
		}
		return TITLE_TAG_REGEX.find(text)?.groupValues?.get(1)?.trim()?.takeUnless { it.isEmpty() }
	}

	private fun fallbackPackage(zip: ZipFile): PackageInfo? {
		val images = zip.entries().asSequence()
			.filter { !it.isDirectory }
			.map { it.name }
			.filter { MimeTypes.getMimeTypeFromExtension(it.substringAfterLast('.', ""))?.isImage == true }
			.sorted()
			.toList()
		if (images.isEmpty()) {
			return null
		}
		return PackageInfo(
			title = null,
			author = null,
			coverEntry = images.first(),
			chapters = listOf(EpubChapter(path = "", title = null, pages = images)),
		)
	}

	private fun resolveRelative(baseDir: String, href: String): String {
		val decoded = runCatchingCancellable {
			URLDecoder.decode(href, "UTF-8")
		}.getOrDefault(href)
		val base = baseDir.trim('/')
		return if (decoded.startsWith("/")) {
			decoded.trimStart('/')
		} else if (base.isEmpty()) {
			decoded
		} else {
			val stack = ArrayDeque(base.split('/'))
			for (segment in decoded.split('/')) {
				when (segment) {
					"" -> {}
					"." -> {}
					".." -> stack.removeLastOrNull()
					else -> stack.addLast(segment)
				}
			}
			stack.joinToString("/")
		}
	}

	private class PackageInfo(
		val title: String?,
		val author: String?,
		val coverEntry: String?,
		val chapters: List<EpubChapter>,
	)

	private class OpfItem(
		val href: String,
		val mediaType: String,
		val properties: String,
	)

	private class EpubChapter(
		val path: String,
		val title: String?,
		val pages: List<String>,
	)

	private companion object {

		const val MEDIA_TYPE_XHTML = "application/xhtml+xml"

		val IMG_TAG_REGEX = Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE)
		val SRC_ATTR_REGEX = Regex("src\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
		val TITLE_TAG_REGEX = Regex("<title[^>]*>([^<]*)</title>", RegexOption.IGNORE_CASE)
	}
}