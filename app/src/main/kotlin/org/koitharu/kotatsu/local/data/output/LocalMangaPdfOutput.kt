package org.koitharu.kotatsu.local.data.output

import org.koitharu.kotatsu.parsers.model.Manga
import java.io.File
import java.util.zip.ZipFile

/**
 * Download output that produces a single PDF file.
 *
 * Pages are buffered into a temporary ZIP archive during the download and
 * [PdfBuilder] converts them into the final PDF on [finish].
 */
class LocalMangaPdfOutput(
	rootFile: File,
	manga: Manga,
) : LocalMangaArchiveOutput(rootFile, manga) {

	override val isLocalReadable = false

	override fun build(
		archive: ZipFile,
		chapters: List<ArchiveChapter>,
		coverEntryName: String?,
		target: File,
	) {
		PdfBuilder.build(
			archive = archive,
			chapters = chapters,
			coverEntryName = coverEntryName,
			target = target,
			title = manga.title,
		)
	}
}
