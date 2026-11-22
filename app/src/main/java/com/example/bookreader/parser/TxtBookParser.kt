package com.example.bookreader.parser

import com.example.bookreader.domain.model.Chapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.nio.charset.Charset
import java.util.regex.Pattern

data class TxtParsedResult(
    val title: String,
    val fullText: String,
    val chapters: List<Chapter>,
    val totalLength: Long
)

object TxtBookParser {

    private val CHAPTER_PATTERN = Pattern.compile(
        "^\\s*(第[0-9一二三四五六七八九十百千万]+[章回卷节集部篇]|Chapter\\s+\\d+|CHAPTER\\s+\\d+).*$",
        Pattern.MULTILINE
    )

    suspend fun parse(file: File): TxtParsedResult = withContext(Dispatchers.IO) {
        val charset = detectCharset(file)
        val text = file.readText(charset)
        val chapters = mutableListOf<Chapter>()
        
        val matcher = CHAPTER_PATTERN.matcher(text)
        var chapterIndex = 0

        while (matcher.find()) {
            val start = matcher.start().toLong()
            val title = matcher.group().trim()

            // 如果开头有一段没有章节名的前言/序言
            if (chapterIndex == 0 && start > 0) {
                chapters.add(
                    Chapter(
                        index = 0,
                        title = "序言 / 前言",
                        startCharOffset = 0L
                    )
                )
                chapterIndex++
            }

            chapters.add(
                Chapter(
                    index = chapterIndex,
                    title = title,
                    startCharOffset = start
                )
            )
            chapterIndex++
        }

        // 如果全文未匹配到章节，则按固定字数虚拟拆分成章节，便于阅读跳转
        if (chapters.isEmpty()) {
            val chunkSize = 5000
            val totalLen = text.length
            var idx = 0
            var offset = 0
            while (offset < totalLen) {
                val end = minOf(offset + chunkSize, totalLen)
                chapters.add(
                    Chapter(
                        index = idx,
                        title = "第 ${idx + 1} 节",
                        startCharOffset = offset.toLong()
                    )
                )
                idx++
                offset = end
            }
        }

        TxtParsedResult(
            title = file.nameWithoutExtension,
            fullText = text,
            chapters = chapters,
            totalLength = text.length.toLong()
        )
    }

    fun detectCharset(file: File): Charset {
        val buffer = ByteArray(4096)
        FileInputStream(file).use { input ->
            val bytesRead = input.read(buffer)
            if (bytesRead > 0) {
                // BOM 检测
                if (buffer.size >= 3 && buffer[0] == 0xEF.toByte() && buffer[1] == 0xBB.toByte() && buffer[2] == 0xBF.toByte()) {
                    return Charsets.UTF_8
                }
                if (buffer.size >= 2 && buffer[0] == 0xFE.toByte() && buffer[1] == 0xFF.toByte()) {
                    return Charsets.UTF_16BE
                }
                if (buffer.size >= 2 && buffer[0] == 0xFF.toByte() && buffer[1] == 0xFE.toByte()) {
                    return Charsets.UTF_16LE
                }

                // 尝试 UTF-8 解码检验
                if (isValidUtf8(buffer, bytesRead)) {
                    return Charsets.UTF_8
                }
            }
        }
        // 中文环境优先回退到 GBK，否则 UTF-8
        return try {
            Charset.forName("GBK")
        } catch (e: Exception) {
            Charsets.UTF_8
        }
    }

    private fun isValidUtf8(bytes: ByteArray, length: Int): Boolean {
        var i = 0
        while (i < length) {
            val byte = bytes[i].toInt() and 0xFF
            when {
                byte <= 0x7F -> i++
                byte in 0xC2..0xDF -> {
                    if (i + 1 >= length) return true
                    val b2 = bytes[i + 1].toInt() and 0xFF
                    if (b2 !in 0x80..0xBF) return false
                    i += 2
                }
                byte in 0xE0..0xEF -> {
                    if (i + 2 >= length) return true
                    val b2 = bytes[i + 1].toInt() and 0xFF
                    val b3 = bytes[i + 2].toInt() and 0xFF
                    if (b2 !in 0x80..0xBF || b3 !in 0x80..0xBF) return false
                    i += 3
                }
                byte in 0xF0..0xF4 -> {
                    if (i + 3 >= length) return true
                    val b2 = bytes[i + 1].toInt() and 0xFF
                    val b3 = bytes[i + 2].toInt() and 0xFF
                    val b4 = bytes[i + 3].toInt() and 0xFF
                    if (b2 !in 0x80..0xBF || b3 !in 0x80..0xBF || b4 !in 0x80..0xBF) return false
                    i += 4
                }
                else -> return false
            }
        }
        return true
    }
}
