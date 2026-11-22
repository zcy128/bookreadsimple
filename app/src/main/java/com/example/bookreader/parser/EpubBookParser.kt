package com.example.bookreader.parser

import android.util.Xml
import com.example.bookreader.domain.model.Chapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.StringReader
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

data class EpubManifestItem(
    val id: String,
    val href: String,
    val mediaType: String
)

data class EpubParsedBook(
    val title: String,
    val author: String,
    val extractedDir: File,
    val opfDir: String,
    val chapters: List<Chapter>,
    val spineItemHrefs: List<String>
)

object EpubBookParser {

    suspend fun parse(epubFile: File, outputBaseDir: File): EpubParsedBook = withContext(Dispatchers.IO) {
        val bookUnzipDir = File(outputBaseDir, "epub_${epubFile.nameWithoutExtension}_${epubFile.length()}")
        if (!bookUnzipDir.exists() || bookUnzipDir.list().isNullOrEmpty()) {
            unzip(epubFile, bookUnzipDir)
        }

        // 1. 读取 META-INF/container.xml
        val containerFile = File(bookUnzipDir, "META-INF/container.xml")
        val opfRelativePath = parseContainerForOpfPath(containerFile)
        val opfFile = File(bookUnzipDir, opfRelativePath)
        val opfDir = opfFile.parentFile?.relativeTo(bookUnzipDir)?.path?.replace('\\', '/') ?: ""

        // 2. 解析 OPF
        val opfContent = opfFile.readText()
        val (title, author, manifestMap, spineIdRefs) = parseOpf(opfContent)

        // 3. 构建章节清单
        val chapters = mutableListOf<Chapter>()
        val spineHrefs = mutableListOf<String>()

        spineIdRefs.forEachIndexed { index, idRef ->
            val item = manifestMap[idRef]
            if (item != null) {
                val fullPath = if (opfDir.isNotEmpty()) "$opfDir/${item.href}" else item.href
                spineHrefs.add(fullPath)
                chapters.add(
                    Chapter(
                        index = index,
                        title = "第 ${index + 1} 章",
                        contentPath = fullPath
                    )
                )
            }
        }

        // 尝试从 TOC (ncx / nav.xhtml) 提取章节标题，并基于文件路径精确映射（彻底消除章节偏差）
        try {
            val tocItems = mutableListOf<TocItem>()

            // 1. 尝试 NCX (EPUB 2 / EPUB 3 兼容)
            val ncxItem = manifestMap.values.find { it.mediaType.contains("ncx") || it.href.endsWith(".ncx") }
            if (ncxItem != null) {
                val ncxPath = if (opfDir.isNotEmpty()) "$opfDir/${ncxItem.href}" else ncxItem.href
                val ncxFile = File(bookUnzipDir, ncxPath)
                if (ncxFile.exists()) {
                    tocItems.addAll(parseNcx(ncxFile.readText()))
                }
            }

            // 2. 若 NCX 为空，尝试 EPUB 3 Nav HTML
            if (tocItems.isEmpty()) {
                val navItem = manifestMap.values.find {
                    it.href.contains("nav", ignoreCase = true) || it.id.contains("nav", ignoreCase = true)
                }
                if (navItem != null) {
                    val navPath = if (opfDir.isNotEmpty()) "$opfDir/${navItem.href}" else navItem.href
                    val navFile = File(bookUnzipDir, navPath)
                    if (navFile.exists()) {
                        tocItems.addAll(parseNavHtml(navFile.readText()))
                    }
                }
            }

            // 3. 将 TOC 标题按目标文件名进行精准匹配，杜绝封面/扉页导致的位移（Off-by-one）
            if (tocItems.isNotEmpty()) {
                val tocFileNameMap = mutableMapOf<String, String>()
                tocItems.forEach { item ->
                    val fileName = File(item.src).name.lowercase()
                    if (!tocFileNameMap.containsKey(fileName)) {
                        tocFileNameMap[fileName] = item.title
                    }
                }

                for (i in chapters.indices) {
                    val chapter = chapters[i]
                    val fileName = File(chapter.contentPath).name.lowercase()
                    val matchedTitle = tocFileNameMap[fileName]

                    if (!matchedTitle.isNullOrEmpty()) {
                        chapters[i] = chapter.copy(title = matchedTitle)
                    } else {
                        val lowerPath = chapter.contentPath.lowercase()
                        val smartTitle = when {
                            lowerPath.contains("cover") -> "封面"
                            lowerPath.contains("title") -> "扉页"
                            lowerPath.contains("copyright") -> "版权信息"
                            lowerPath.contains("toc") || lowerPath.contains("content") -> "目录"
                            lowerPath.contains("preface") || lowerPath.contains("intro") -> "序言 / 前言"
                            lowerPath.contains("afterword") || lowerPath.contains("epilogue") -> "后记 / 尾声"
                            else -> chapters[i].title
                        }
                        chapters[i] = chapter.copy(title = smartTitle)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        EpubParsedBook(
            title = title.ifEmpty { epubFile.nameWithoutExtension },
            author = author.ifEmpty { "未知作者" },
            extractedDir = bookUnzipDir,
            opfDir = opfDir,
            chapters = chapters,
            spineItemHrefs = spineHrefs
        )
    }

    private fun unzip(zipFile: File, targetDir: File) {
        if (!targetDir.exists()) targetDir.mkdirs()
        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            val buffer = ByteArray(8192)
            while (entry != null) {
                val newFile = File(targetDir, entry.name)
                // 防止 Zip Slip 漏洞
                if (!newFile.canonicalPath.startsWith(targetDir.canonicalPath)) {
                    entry = zis.nextEntry
                    continue
                }
                if (entry.isDirectory) {
                    newFile.mkdirs()
                } else {
                    newFile.parentFile?.mkdirs()
                    FileOutputStream(newFile).use { fos ->
                        var len: Int
                        while (zis.read(buffer).also { len = it } > 0) {
                            fos.write(buffer, 0, len)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun parseContainerForOpfPath(containerFile: File): String {
        if (!containerFile.exists()) return "OEBPS/content.opf"
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(containerFile.readText()))
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && parser.name.equals("rootfile", ignoreCase = true)) {
                val fullPath = parser.getAttributeValue(null, "full-path")
                if (!fullPath.isNullOrEmpty()) {
                    return fullPath
                }
            }
            eventType = parser.next()
        }
        return "content.opf"
    }

    private data class OpfParseResult(
        val title: String,
        val author: String,
        val manifest: Map<String, EpubManifestItem>,
        val spine: List<String>
    )

    private fun parseOpf(opfText: String): OpfParseResult {
        var title = ""
        var author = ""
        val manifest = mutableMapOf<String, EpubManifestItem>()
        val spine = mutableListOf<String>()

        val parser = Xml.newPullParser()
        parser.setInput(StringReader(opfText))
        var eventType = parser.eventType

        var currentTag = ""
        val titleBuilder = StringBuilder()
        val authorBuilder = StringBuilder()

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name ?: ""
                    when (currentTag.lowercase()) {
                        "item" -> {
                            val id = parser.getAttributeValue(null, "id") ?: ""
                            val href = parser.getAttributeValue(null, "href") ?: ""
                            val mediaType = parser.getAttributeValue(null, "media-type") ?: ""
                            if (id.isNotEmpty() && href.isNotEmpty()) {
                                manifest[id] = EpubManifestItem(id, href, mediaType)
                            }
                        }
                        "itemref" -> {
                            val idref = parser.getAttributeValue(null, "idref") ?: ""
                            if (idref.isNotEmpty()) {
                                spine.add(idref)
                            }
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    val rawText = parser.text ?: ""
                    if (currentTag.endsWith("title", ignoreCase = true)) {
                        titleBuilder.append(rawText)
                    } else if (currentTag.endsWith("creator", ignoreCase = true)) {
                        authorBuilder.append(rawText)
                    }
                }
                XmlPullParser.END_TAG -> {
                    val endTagName = parser.name ?: ""
                    if (endTagName.endsWith("title", ignoreCase = true) && title.isEmpty()) {
                        val extracted = titleBuilder.toString().trim()
                        if (extracted.isNotEmpty()) {
                            title = extracted
                        }
                    } else if (endTagName.endsWith("creator", ignoreCase = true) && author.isEmpty()) {
                        val extracted = authorBuilder.toString().trim()
                        if (extracted.isNotEmpty()) {
                            author = extracted
                        }
                    }
                    currentTag = ""
                }
            }
            eventType = parser.next()
        }

        return OpfParseResult(title, author, manifest, spine)
    }

    data class TocItem(
        val title: String,
        val src: String
    )

    private fun parseNcx(ncxText: String): List<TocItem> {
        val items = mutableListOf<TocItem>()
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(ncxText))
        var eventType = parser.eventType

        var currentTitle = ""
        var isText = false
        val textBuilder = StringBuilder()

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name.lowercase()) {
                        "navpoint" -> {
                            currentTitle = ""
                        }
                        "text" -> {
                            isText = true
                            textBuilder.setLength(0)
                        }
                        "content" -> {
                            val src = parser.getAttributeValue(null, "src") ?: ""
                            val cleanSrc = src.substringBefore('#').trim()
                            if (currentTitle.isNotEmpty() && cleanSrc.isNotEmpty()) {
                                items.add(TocItem(currentTitle, cleanSrc))
                                currentTitle = ""
                            }
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    if (isText) {
                        textBuilder.append(parser.text ?: "")
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name.lowercase()) {
                        "text" -> {
                            if (isText) {
                                currentTitle = textBuilder.toString().trim()
                                isText = false
                            }
                        }
                    }
                }
            }
            eventType = parser.next()
        }
        return items
    }

    private fun parseNavHtml(navText: String): List<TocItem> {
        val items = mutableListOf<TocItem>()
        val parser = Xml.newPullParser()
        try {
            parser.setInput(StringReader(navText))
            var eventType = parser.eventType
            var inAnchor = false
            var currentHref = ""
            val textBuilder = StringBuilder()

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (parser.name.equals("a", ignoreCase = true)) {
                            val href = parser.getAttributeValue(null, "href") ?: ""
                            if (href.isNotEmpty()) {
                                inAnchor = true
                                currentHref = href.substringBefore('#').trim()
                                textBuilder.setLength(0)
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inAnchor) {
                            textBuilder.append(parser.text ?: "")
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name.equals("a", ignoreCase = true) && inAnchor) {
                            val title = textBuilder.toString().trim()
                            if (title.isNotEmpty() && currentHref.isNotEmpty()) {
                                items.add(TocItem(title, currentHref))
                            }
                            inAnchor = false
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            // fallback
        }
        return items
    }
}
