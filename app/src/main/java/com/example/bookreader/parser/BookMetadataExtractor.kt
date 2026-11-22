package com.example.bookreader.parser

import com.example.bookreader.domain.model.BookFormat
import java.io.File

data class ExtractedMetadata(
    val title: String,
    val author: String
)

object BookMetadataExtractor {

    suspend fun extract(
        file: File,
        format: BookFormat,
        originalFileName: String,
        cacheBaseDir: File
    ): ExtractedMetadata {
        return when (format) {
            BookFormat.EPUB, BookFormat.KEPUB -> extractFromParsedBook(file, originalFileName) {
                EpubBookParser.parse(file, cacheBaseDir)
            }
            BookFormat.MOBI -> extractFromParsedBook(file, originalFileName) {
                MobiBookParser.parse(file, cacheBaseDir)
            }
            BookFormat.TXT -> extractFromTxt(file, originalFileName)
            BookFormat.PDF -> extractFromPdf(file, originalFileName)
        }
    }

    private suspend fun extractFromParsedBook(
        file: File,
        originalFileName: String,
        parseBlock: suspend () -> EpubParsedBook
    ): ExtractedMetadata {
        val fallback = cleanFileName(originalFileName)
        return try {
            val parsed = parseBlock()
            var title = parsed.title.trim()
            var author = parsed.author.trim()

            // 过滤无效或通用的占位标题
            val isInvalidTitle = title.isEmpty() ||
                    title.equals("unknown", ignoreCase = true) ||
                    title.equals("untitled", ignoreCase = true) ||
                    title.equals(file.nameWithoutExtension, ignoreCase = true)

            if (isInvalidTitle) {
                title = fallback.title
            }

            if (author.isEmpty() || author == "未知作者") {
                author = fallback.author
            }

            ExtractedMetadata(
                title = title.ifEmpty { fallback.title },
                author = author.ifEmpty { fallback.author }
            )
        } catch (e: Exception) {
            e.printStackTrace()
            fallback
        }
    }

    private suspend fun extractFromTxt(file: File, originalFileName: String): ExtractedMetadata {
        val fallback = cleanFileName(originalFileName)
        var detectedTitle = ""
        var detectedAuthor = ""

        try {
            val charset = TxtBookParser.detectCharset(file)
            file.bufferedReader(charset).use { reader ->
                var linesRead = 0
                while (linesRead < 50) {
                    val line = reader.readLine() ?: break
                    linesRead++
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) continue

                    // 1. 查找 《书名》
                    if (detectedTitle.isEmpty()) {
                        val bookMarkMatch = Regex("《([^》]{1,60})》").find(trimmed)
                        if (bookMarkMatch != null) {
                            detectedTitle = bookMarkMatch.groupValues[1].trim()
                        }
                    }

                    // 2. 查找 "书名：xxx" 或 "作品名：xxx"
                    if (detectedTitle.isEmpty()) {
                        val titlePrefixMatch = Regex("^(?:书名|作品名|作品)[：:]\\s*(.{1,60})$").find(trimmed)
                        if (titlePrefixMatch != null) {
                            detectedTitle = titlePrefixMatch.groupValues[1].trim()
                        }
                    }

                    // 3. 查找 "作者：xxx"
                    if (detectedAuthor.isEmpty()) {
                        val authorMatch = Regex("^(?:作者|作\\s*者)[：:]\\s*(.{1,40})$").find(trimmed)
                        if (authorMatch != null) {
                            detectedAuthor = authorMatch.groupValues[1].trim()
                        }
                    }

                    if (detectedTitle.isNotEmpty() && detectedAuthor.isNotEmpty()) {
                        break
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return ExtractedMetadata(
            title = detectedTitle.ifEmpty { fallback.title },
            author = detectedAuthor.ifEmpty { fallback.author }
        )
    }

    private fun extractFromPdf(file: File, originalFileName: String): ExtractedMetadata {
        return cleanFileName(originalFileName)
    }

    fun cleanFileName(fileName: String): ExtractedMetadata {
        var name = fileName.substringBeforeLast('.').trim()

        // 1. 若文件名中含有 《书名》
        val bookMark = Regex("《([^》]+)》").find(name)
        if (bookMark != null) {
            val title = bookMark.groupValues[1].trim()
            val remaining = name.replace(bookMark.value, "").trim()
            val authorMatch = Regex("(?:作者[：:]|作\\s*者[：:]|-|——)\\s*([\\w\\u4e00-\\u9fa5·]+)").find(remaining)
            val author = authorMatch?.groupValues?.get(1)?.trim() ?: "本地导入"
            return ExtractedMetadata(title, author)
        }

        // 2. 检查 "作者：xxx"
        val authorPrefixMatch = Regex("(?:作者|作\\s*者)[：:]\\s*([\\w\\u4e00-\\u9fa5·]+)").find(name)
        var author = "本地导入"
        if (authorPrefixMatch != null) {
            author = authorPrefixMatch.groupValues[1].trim()
            name = name.replace(authorPrefixMatch.value, "").trim()
        }

        // 3. 检查 "书名 - 作者" 格式
        if (name.contains(" - ") || name.contains(" – ")) {
            val parts = name.split(Regex("\\s*[-–]\\s*")).filter { it.isNotBlank() }
            if (parts.size == 2) {
                val p1 = cleanBrackets(parts[0])
                val p2 = cleanBrackets(parts[1])
                // 通常长度较短或包含中文姓名的为作者，或者格式为 "书名 - 作者"
                return ExtractedMetadata(title = p1, author = if (author != "本地导入") author else p2)
            }
        }

        // 4. 清理括号与常见标签
        val cleanedTitle = cleanBrackets(name)
        return ExtractedMetadata(
            title = cleanedTitle.ifEmpty { fileName.substringBeforeLast('.') },
            author = author
        )
    }

    private fun cleanBrackets(raw: String): String {
        return raw
            .replace(Regex("\\[[^\\]]*\\]"), "")
            .replace(Regex("【[^】]*】"), "")
            .replace(Regex("\\([^\\)]*\\)"), "")
            .replace(Regex("（[^）]*）"), "")
            .replace(Regex("(?i)(精校版|全本|全集|最新版|未删节|校对版|精排版|精编版)"), "")
            .replace('_', ' ')
            .trim()
    }
}
