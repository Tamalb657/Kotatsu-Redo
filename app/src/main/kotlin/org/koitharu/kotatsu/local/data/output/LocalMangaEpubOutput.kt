package org.koitharu.kotatsu.local.data.output

import org.koitharu.kotatsu.parsers.model.Manga
import java.io.File
import java.util.zip.ZipFile

/**
 * Download output that produces a single EPUB file.
 *
 * Pages are buffered into a temporary ZIP archive during the download and
 * [EpubBuilder] converts them into the final EPUB on [finish].
 */
class LocalMangaEpubOutput(
	rootFile: File,
	manga: Manga,
) : LocalMangaArchiveOutput(rootFile, manga) {

	override fun build(
		archive: ZipFile,
		chapters: List<ArchiveChapter>,
		coverEntryName: String?,
		target: File,
	) {
		EpubBuilder.build(
			archive = archive,
			chapters = chapters,
			coverEntryName = coverEntryName,
			target = target,
			title = manga.title,
			author = manga.author,
		)
	}
}
