package org.koitharu.kotatsu.local.data.input

import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.util.ext.URI_SCHEME_PDF
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.util.longHashCode
import org.koitharu.kotatsu.parsers.util.toTitleCase
import java.io.File

/**
 * Reads manga from a PDF file via [PdfRenderer]. The whole document is a single chapter;
 * pages are rendered on demand by [org.koitharu.kotatsu.core.image.PdfPageFetcher].
 */
class PdfMangaParser(private val file: File) {

	private fun <R> withRenderer(block: (PdfRenderer) -> R): R {
		ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
			PdfRenderer(pfd).use { renderer ->
				return block(renderer)
			}
		}
	}

	private fun pageCount(): Int = withRenderer { it.pageCount }

	suspend fun getManga(withDetails: Boolean): LocalManga = runInterruptible(Dispatchers.IO) {
		val count = pageCount()
		val manga = Manga(
			id = file.absolutePath.longHashCode(),
			title = file.name.removeSuffix(".pdf").replace('_', ' ').toTitleCase(),
			url = file.toUri().toString(),
			publicUrl = file.toUri().toString(),
			source = LocalMangaSource,
			coverUrl = buildPageUri(0).toString(),
			chapters = if (withDetails) {
				listOf(
					MangaChapter(
						id = file.absolutePath.longHashCode(),
						title = "All pages",
						number = 1f,
						volume = 0,
						source = LocalMangaSource,
						uploadDate = 0L,
						url = buildChapterUri().toString(),
						scanlator = null,
						branch = null,
					),
				)
			} else {
				null
			},
			altTitles = emptySet(),
			rating = -1f,
			contentRating = null,
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			largeCoverUrl = null,
			description = null,
		)
		LocalManga(manga, file)
	}

	suspend fun getMangaInfo(): Manga? = runInterruptible(Dispatchers.IO) {
		pageCount()
		Manga(
			id = file.absolutePath.longHashCode(),
			title = file.name.removeSuffix(".pdf").replace('_', ' ').toTitleCase(),
			url = file.toUri().toString(),
			publicUrl = file.toUri().toString(),
			source = LocalMangaSource,
			coverUrl = buildPageUri(0).toString(),
			chapters = null,
			altTitles = emptySet(),
			rating = -1f,
			contentRating = null,
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			largeCoverUrl = null,
			description = null,
		)
	}

	suspend fun getPages(chapter: MangaChapter): List<MangaPage> = runInterruptible(Dispatchers.IO) {
		val count = pageCount()
		(0 until count).map { index ->
			val url = buildPageUri(index).toString()
			MangaPage(
				id = url.longHashCode(),
				url = url,
				preview = null,
				source = LocalMangaSource,
			)
		}
	}

	private fun buildChapterUri(): Uri = file.toUri().buildUpon()
		.fragment(PDF_FRAGMENT_ALL)
		.build()

	private fun buildPageUri(index: Int): Uri = file.toUri().buildUpon()
		.scheme(URI_SCHEME_PDF)
		.fragment(index.toString())
		.build()

	private companion object {

		const val PDF_FRAGMENT_ALL = "all"
	}
}
