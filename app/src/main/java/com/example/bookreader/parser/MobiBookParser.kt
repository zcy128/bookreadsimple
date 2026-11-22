package com.example.bookreader.parser

import com.example.bookreader.domain.model.Chapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset

/**
 * Pure-Kotlin parser for Mobipocket / PalmDOC (.mobi) ebooks.
 * Decompresses text records, extracts embedded images, splits chapters into HTML files,
 * and outputs [EpubParsedBook] so [com.example.bookreader.ui.reader.EpubReaderView] can render it directly.
 */
object MobiBookParser {

    private const val COMPRESSION_NONE = 1
    private const val COMPRESSION_PALMDOC = 2
    private const val COMPRESSION_HUFF_CDIC = 17480

    private const val EXTH_AUTHOR = 100
    private const val EXTH_UPDATED_TITLE = 503

    private data class MobiHeaderInfo(
        val pdbTitle: String,
        val fullName: String,
        val author: String,
        val compression: Int,
        val textRecordCount: Int,
        val charset: Charset,
        val firstImageIndex: Int,
        val huffOffset: Int,
        val huffCount: Int,
        val extraDataFlags: Int
    )

    suspend fun parse(mobiFile: File, outputBaseDir: File): EpubParsedBook = withContext(Dispatchers.IO) {
        val bookCacheDir = File(outputBaseDir, "mobi_${mobiFile.nameWithoutExtension}_${mobiFile.length()}")
        val markerFile = File(bookCacheDir, ".parsed_ok")
        val existingChapters = if (markerFile.exists()) {
            loadCachedChapters(bookCacheDir)
        } else {
            emptyList()
        }

        RandomAccessFile(mobiFile, "r").use { raf ->
            val fileLength = raf.length()
            if (fileLength < 80) {
                throw IllegalArgumentException("无效的 MOBI 文件：文件过小")
            }

            // 1. PDB Header
            val pdbNameBytes = ByteArray(32)
            raf.readFully(pdbNameBytes)
            val pdbTitle = pdbNameBytes.takeWhile { it != 0.toByte() }.toByteArray()
                .toString(Charsets.ISO_8859_1).trim()

            raf.seek(76)
            val numRecords = readUInt16BE(raf)
            if (numRecords <= 1) {
                throw IllegalArgumentException("无效的 MOBI 文件：记录数不足")
            }

            val recordOffsets = LongArray(numRecords + 1)
            for (i in 0 until numRecords) {
                raf.seek(78L + i * 8L)
                recordOffsets[i] = readUInt32BE(raf)
            }
            recordOffsets[numRecords] = fileLength

            fun readRecord(index: Int): ByteArray {
                if (index !in 0 until numRecords) return ByteArray(0)
                val start = recordOffsets[index]
                val end = recordOffsets[index + 1].coerceAtMost(fileLength)
                val len = (end - start).toInt()
                if (len <= 0) return ByteArray(0)
                val buf = ByteArray(len)
                raf.seek(start)
                raf.readFully(buf)
                return buf
            }

            // 2. Record 0: PalmDOC + MOBI + EXTH
            val rec0 = readRecord(0)
            val header = parseRecord0(rec0, pdbTitle, numRecords)

            if (existingChapters.isNotEmpty()) {
                val spineHrefs = existingChapters.map { it.contentPath }
                return@withContext EpubParsedBook(
                    title = header.fullName.ifEmpty { mobiFile.nameWithoutExtension },
                    author = header.author.ifEmpty { "未知作者" },
                    extractedDir = bookCacheDir,
                    opfDir = "",
                    chapters = existingChapters,
                    spineItemHrefs = spineHrefs
                )
            }

            if (bookCacheDir.exists()) {
                bookCacheDir.deleteRecursively()
            }
            bookCacheDir.mkdirs()

            // 3. Decompress text records (1 .. textRecordCount)
            val huffDecoder = if (header.compression == COMPRESSION_HUFF_CDIC) {
                val huffData = readRecord(header.huffOffset)
                val cdicRecords = (1 until header.huffCount).map { offset ->
                    readRecord(header.huffOffset + offset)
                }
                HuffCdicDecoder(huffData, cdicRecords)
            } else {
                null
            }

            val htmlStream = ByteArrayOutputStream()
            val maxTextRec = header.textRecordCount.coerceAtMost(numRecords - 1)
            for (i in 1..maxTextRec) {
                val raw = readRecord(i)
                val trimmed = stripTrailingExtraData(raw, header.extraDataFlags)
                if (trimmed.isEmpty()) continue
                val decompressed = when (header.compression) {
                    COMPRESSION_NONE -> trimmed
                    COMPRESSION_PALMDOC -> decompressPalmDoc(trimmed)
                    COMPRESSION_HUFF_CDIC -> huffDecoder?.decode(trimmed)
                        ?: throw IllegalStateException("HUFF/CDIC 解码器初始化失败")
                    else -> throw IllegalArgumentException("不支持的 MOBI 压缩格式: ${header.compression}")
                }
                htmlStream.write(decompressed)
            }

            val rawHtml = htmlStream.toByteArray().toString(header.charset)

            // 4. Extract embedded images starting at firstImageIndex
            val imageFileMap = mutableMapOf<Int, String>()
            if (header.firstImageIndex in 1 until numRecords) {
                var imgIndex = 1
                for (recIdx in header.firstImageIndex until numRecords) {
                    val imgBytes = readRecord(recIdx)
                    val ext = detectImageExtension(imgBytes)
                    if (ext != null) {
                        val fileName = "img_${imgIndex}.$ext"
                        File(bookCacheDir, fileName).writeBytes(imgBytes)
                        imageFileMap[imgIndex] = fileName
                    }
                    imgIndex++
                }
            }

            // 5. Rewrite image recindex attributes and split into chapters
            val rewrittenHtml = rewriteImageRefs(rawHtml, imageFileMap)
            val chapters = splitAndWriteChapters(rewrittenHtml, bookCacheDir)
            markerFile.writeText(chapters.joinToString("\n") { "${it.contentPath}\t${it.title}" })

            EpubParsedBook(
                title = header.fullName.ifEmpty { mobiFile.nameWithoutExtension },
                author = header.author.ifEmpty { "未知作者" },
                extractedDir = bookCacheDir,
                opfDir = "",
                chapters = chapters,
                spineItemHrefs = chapters.map { it.contentPath }
            )
        }
    }

    private fun loadCachedChapters(bookCacheDir: File): List<Chapter> {
        val markerFile = File(bookCacheDir, ".parsed_ok")
        if (!markerFile.exists()) return emptyList()
        return markerFile.readLines().mapIndexedNotNull { idx, line ->
            val parts = line.split('\t', limit = 2)
            val path = parts.getOrNull(0)?.trim().orEmpty()
            val title = parts.getOrNull(1)?.trim().orEmpty().ifEmpty { "第 ${idx + 1} 章" }
            if (path.isNotEmpty() && File(bookCacheDir, path).exists()) {
                Chapter(index = idx, title = title, contentPath = path)
            } else {
                null
            }
        }
    }

    private fun parseRecord0(rec0: ByteArray, pdbTitle: String, numRecords: Int): MobiHeaderInfo {
        if (rec0.size < 16) {
            throw IllegalArgumentException("无效的 MOBI 文件：Record 0 损坏")
        }
        val compression = u16(rec0, 0)
        val textRecordCount = u16(rec0, 8)
        val encryptionType = u16(rec0, 12)
        if (encryptionType != 0) {
            throw IllegalArgumentException("不支持受 DRM 加密保护的 MOBI 文件")
        }

        val hasMobiMagic = rec0.size >= 24 &&
            rec0[16] == 'M'.code.toByte() &&
            rec0[17] == 'O'.code.toByte() &&
            rec0[18] == 'B'.code.toByte() &&
            rec0[19] == 'I'.code.toByte()

        if (!hasMobiMagic) {
            return MobiHeaderInfo(
                pdbTitle = pdbTitle,
                fullName = pdbTitle,
                author = "",
                compression = compression,
                textRecordCount = textRecordCount,
                charset = Charsets.UTF_8,
                firstImageIndex = numRecords,
                huffOffset = 0,
                huffCount = 0,
                extraDataFlags = 0
            )
        }

        val mobiHeaderLen = u32(rec0, 20).toInt()
        val encodingCode = u32(rec0, 28).toInt()
        val charset = when (encodingCode) {
            65001 -> Charsets.UTF_8
            1252 -> Charset.forName("windows-1252")
            else -> Charsets.UTF_8
        }

        val fullNameOffset = if (rec0.size >= 92) u32(rec0, 84).toInt() else 0
        val fullNameLength = if (rec0.size >= 92) u32(rec0, 88).toInt() else 0
        var fullName = if (fullNameOffset > 0 && fullNameLength > 0 && fullNameOffset + fullNameLength <= rec0.size) {
            String(rec0, fullNameOffset, fullNameLength, charset).trim()
        } else {
            pdbTitle
        }

        val firstImageIndex = if (rec0.size >= 112) u32(rec0, 108).toInt() else numRecords
        val huffOffset = if (rec0.size >= 120) u32(rec0, 112).toInt() else 0
        val huffCount = if (rec0.size >= 120) u32(rec0, 116).toInt() else 0
        val exthFlags = if (rec0.size >= 132) u32(rec0, 128).toInt() else 0
        val extraDataFlags = if (mobiHeaderLen >= 228 && rec0.size >= 244) u16(rec0, 242) else 0

        var author = ""
        val exthStart = 16 + mobiHeaderLen
        if ((exthFlags and 0x40) != 0 && exthStart + 12 <= rec0.size) {
            val isExth = rec0[exthStart] == 'E'.code.toByte() &&
                rec0[exthStart + 1] == 'X'.code.toByte() &&
                rec0[exthStart + 2] == 'T'.code.toByte() &&
                rec0[exthStart + 3] == 'H'.code.toByte()
            if (isExth) {
                val exthCount = u32(rec0, exthStart + 8).toInt()
                var pos = exthStart + 12
                val authors = mutableListOf<String>()
                for (i in 0 until exthCount) {
                    if (pos + 8 > rec0.size) break
                    val type = u32(rec0, pos).toInt()
                    val len = u32(rec0, pos + 4).toInt()
                    if (len < 8 || pos + len > rec0.size) break
                    val dataLen = len - 8
                    when (type) {
                        EXTH_AUTHOR -> {
                            val a = String(rec0, pos + 8, dataLen, charset).trim()
                            if (a.isNotEmpty()) authors.add(a)
                        }
                        EXTH_UPDATED_TITLE -> {
                            val t = String(rec0, pos + 8, dataLen, charset).trim()
                            if (t.isNotEmpty()) fullName = t
                        }
                    }
                    pos += len
                }
                if (authors.isNotEmpty()) {
                    author = authors.joinToString(", ")
                }
            }
        }

        return MobiHeaderInfo(
            pdbTitle = pdbTitle,
            fullName = fullName.ifEmpty { pdbTitle },
            author = author,
            compression = compression,
            textRecordCount = textRecordCount,
            charset = charset,
            firstImageIndex = firstImageIndex,
            huffOffset = huffOffset,
            huffCount = huffCount,
            extraDataFlags = extraDataFlags
        )
    }

    private fun stripTrailingExtraData(data: ByteArray, flags: Int): ByteArray {
        if (flags == 0 || data.isEmpty()) return data
        var end = data.size

        // Strip variable-length trailing entries indicated by bits 1..15
        var shifted = flags ushr 1
        while (shifted != 0 && end > 0) {
            if ((shifted and 1) != 0) {
                val entrySize = readBackwardVarint(data, end)
                if (entrySize in 1..end) {
                    end -= entrySize
                } else {
                    break
                }
            }
            shifted = shifted ushr 1
        }

        // Strip multibyte character overlap indicated by bit 0
        if ((flags and 1) != 0 && end > 0) {
            val overlap = (data[end - 1].toInt() and 0x03) + 1
            if (overlap <= end) {
                end -= overlap
            }
        }
        return if (end == data.size) data else data.copyOf(end)
    }

    private fun readBackwardVarint(data: ByteArray, endExclusive: Int): Int {
        var size = 0
        var shift = 0
        var idx = endExclusive - 1
        while (idx >= 0 && shift < 28) {
            val b = data[idx].toInt() and 0xFF
            size = size or ((b and 0x7F) shl shift)
            shift += 7
            idx--
            if ((b and 0x80) != 0) break
        }
        return size
    }

    private fun decompressPalmDoc(input: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(input.size * 2)
        val buffer = ArrayList<Byte>(input.size * 2)
        var i = 0
        while (i < input.size) {
            val b = input[i++].toInt() and 0xFF
            when {
                b == 0 -> {
                    buffer.add(0)
                }
                b in 1..8 -> {
                    val count = b.coerceAtMost(input.size - i)
                    for (j in 0 until count) {
                        buffer.add(input[i++])
                    }
                }
                b in 0x09..0x7F -> {
                    buffer.add(b.toByte())
                }
                b in 0x80..0xBF -> {
                    if (i >= input.size) break
                    val next = input[i++].toInt() and 0xFF
                    val pair = ((b shl 8) or next) and 0x3FFF
                    val distance = pair ushr 3
                    val length = (pair and 0x07) + 3
                    if (distance in 1..buffer.size) {
                        for (j in 0 until length) {
                            val srcIdx = buffer.size - distance
                            buffer.add(buffer[srcIdx])
                        }
                    }
                }
                else -> {
                    buffer.add(' '.code.toByte())
                    buffer.add((b xor 0x80).toByte())
                }
            }
        }
        val result = ByteArray(buffer.size)
        for (idx in buffer.indices) {
            result[idx] = buffer[idx]
        }
        out.write(result)
        return out.toByteArray()
    }

    private fun detectImageExtension(bytes: ByteArray): String? {
        if (bytes.size < 4) return null
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        val b2 = bytes[2].toInt() and 0xFF
        val b3 = bytes[3].toInt() and 0xFF
        return when {
            b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF -> "jpg"
            b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47 -> "png"
            b0 == 0x47 && b1 == 0x49 && b2 == 0x46 && b3 == 0x38 -> "gif"
            b0 == 0x42 && b1 == 0x4D -> "bmp"
            bytes.size >= 12 &&
                b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46 &&
                bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
                bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte() -> "webp"
            else -> null
        }
    }

    private fun rewriteImageRefs(html: String, imageFileMap: Map<Int, String>): String {
        if (imageFileMap.isEmpty()) return html
        val imgTagRegex = Regex("""<img\b([^>]*)>""", RegexOption.IGNORE_CASE)
        val recIndexRegex = Regex("""\brecindex\s*=\s*["']?0*(\d+)["']?""", RegexOption.IGNORE_CASE)
        val srcRegex = Regex("""\bsrc\s*=\s*["'][^"']*["']""", RegexOption.IGNORE_CASE)

        return imgTagRegex.replace(html) { match ->
            val attrs = match.groupValues[1]
            val recMatch = recIndexRegex.find(attrs) ?: return@replace match.value
            val idx = recMatch.groupValues[1].toIntOrNull() ?: return@replace match.value
            val fileName = imageFileMap[idx] ?: return@replace match.value
            val updatedAttrs = if (srcRegex.containsMatchIn(attrs)) {
                srcRegex.replace(attrs, """src="$fileName"""")
            } else {
                """ src="$fileName"$attrs"""
            }
            "<img$updatedAttrs>"
        }
    }

    private fun splitAndWriteChapters(fullHtml: String, outputDir: File): List<Chapter> {
        val styleMatch = Regex("""<style\b[^>]*>[\s\S]*?</style>""", RegexOption.IGNORE_CASE).find(fullHtml)
        val extractedStyle = styleMatch?.value.orEmpty()

        val bodyMatch = Regex("""<body\b[^>]*>([\s\S]*)</body>""", RegexOption.IGNORE_CASE).find(fullHtml)
        val bodyHtml = bodyMatch?.groupValues?.get(1) ?: fullHtml

        val pageBreakRegex = Regex("""<mbp:pagebreak\s*/?>""", RegexOption.IGNORE_CASE)
        var rawSections = pageBreakRegex.split(bodyHtml)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        // If the book has no pagebreaks or produced a single huge block, split further by headings or size
        if (rawSections.size <= 1) {
            val single = rawSections.firstOrNull() ?: bodyHtml
            rawSections = splitLargeHtmlSection(single)
        } else {
            rawSections = rawSections.flatMap { section ->
                if (section.length > 120_000) splitLargeHtmlSection(section) else listOf(section)
            }
        }

        if (rawSections.isEmpty()) {
            rawSections = listOf("<p>（空内容）</p>")
        }

        val headingRegex = Regex("""<h[1-3]\b[^>]*>([\s\S]*?)</h[1-3]>""", RegexOption.IGNORE_CASE)
        val tagStripRegex = Regex("""<[^>]+>""")

        return rawSections.mapIndexed { index, sectionHtml ->
            val headingText = headingRegex.find(sectionHtml)?.groupValues?.get(1)
                ?.replace(tagStripRegex, "")
                ?.replace(Regex("""\s+"""), " ")
                ?.trim()
                ?.take(48)
                .orEmpty()
            val title = headingText.ifEmpty { "第 ${index + 1} 章" }
            val fileName = "chapter_%04d.html".format(index)
            val wrapped = buildString {
                append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
                if (extractedStyle.isNotEmpty()) append(extractedStyle)
                append("</head><body>")
                append(sectionHtml)
                append("</body></html>")
            }
            File(outputDir, fileName).writeText(wrapped, Charsets.UTF_8)
            Chapter(index = index, title = title, contentPath = fileName)
        }
    }

    private fun splitLargeHtmlSection(html: String, maxChars: Int = 50_000): List<String> {
        if (html.length <= maxChars) return listOf(html)
        val headingSplitRegex = Regex("""(?=<h[12]\b[^>]*>)""", RegexOption.IGNORE_CASE)
        val byHeading = headingSplitRegex.split(html).map { it.trim() }.filter { it.isNotEmpty() }
        if (byHeading.size > 1 && byHeading.all { it.length <= maxChars * 2 }) {
            return byHeading
        }

        val chunks = mutableListOf<String>()
        var start = 0
        while (start < html.length) {
            if (start + maxChars >= html.length) {
                chunks.add(html.substring(start))
                break
            }
            var splitAt = html.indexOf("</p>", start + maxChars, ignoreCase = true)
            if (splitAt != -1) {
                splitAt += 4
            } else {
                splitAt = html.indexOf('>', start + maxChars)
                splitAt = if (splitAt != -1) splitAt + 1 else (start + maxChars).coerceAtMost(html.length)
            }
            chunks.add(html.substring(start, splitAt))
            start = splitAt
        }
        return chunks
    }

    private fun readUInt16BE(raf: RandomAccessFile): Int {
        val b0 = raf.read()
        val b1 = raf.read()
        return ((b0 and 0xFF) shl 8) or (b1 and 0xFF)
    }

    private fun readUInt32BE(raf: RandomAccessFile): Long {
        val b0 = raf.read().toLong() and 0xFF
        val b1 = raf.read().toLong() and 0xFF
        val b2 = raf.read().toLong() and 0xFF
        val b3 = raf.read().toLong() and 0xFF
        return (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
    }

    private fun u16(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
    }

    private fun u32(bytes: ByteArray, offset: Int): Long {
        return ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)
    }

    /**
     * Minimal HUFF/CDIC decompressor for high-compression MOBI files.
     */
    private class HuffCdicDecoder(huff: ByteArray, cdicRecords: List<ByteArray>) {
        private val dict1 = LongArray(256)
        private val dict2 = LongArray(64)
        private val dictionary = mutableListOf<Pair<ByteArray, Boolean>>()
        private var entryBits = 0

        init {
            require(huff.size >= 24 + 256 * 4 + 64 * 4) { "无效的 HUFF 表" }
            val off1 = u32(huff, 16).toInt()
            val off2 = u32(huff, 20).toInt()
            for (i in 0 until 256) {
                dict1[i] = u32LE(huff, off1 + i * 4)
            }
            for (i in 0 until 64) {
                dict2[i] = u32LE(huff, off2 + i * 4)
            }

            if (cdicRecords.isNotEmpty()) {
                val first = cdicRecords[0]
                entryBits = u32(first, 12).toInt()
                for (cdic in cdicRecords) {
                    if (cdic.size < 16) continue
                    val numPhrases = u32(cdic, 8).toInt()
                    val slicesToRead = numPhrases.coerceAtMost((cdic.size - 16) / 2)
                    for (i in 0 until slicesToRead) {
                        val sliceOffset = 16 + u16(cdic, 16 + i * 2)
                        if (sliceOffset + 2 > cdic.size) break
                        val flagAndLen = u16(cdic, sliceOffset)
                        val len = flagAndLen and 0x7FFF
                        val isLeaf = (flagAndLen and 0x8000) != 0
                        if (sliceOffset + 2 + len <= cdic.size) {
                            val slice = cdic.copyOfRange(sliceOffset + 2, sliceOffset + 2 + len)
                            dictionary.add(slice to isLeaf)
                        }
                    }
                }
            }
        }

        fun decode(data: ByteArray): ByteArray {
            val out = ByteArrayOutputStream(data.size * 2)
            unpack(BitReader(data), out, depth = 0)
            return out.toByteArray()
        }

        private fun unpack(bits: BitReader, out: ByteArrayOutputStream, depth: Int) {
            if (depth > 32) return
            while (bits.left() > 0) {
                val dw = bits.peek(32)
                val v = dict1[((dw ushr 24) and 0xFF).toInt()]
                var codelen = (v and 0x1F).toInt()
                val term = (v and 0x80) != 0L
                var code = dw ushr (32 - codelen)
                var r = v ushr 8

                if (!term) {
                    while (codelen < 32 && code < dict2[(codelen - 1) * 2]) {
                        codelen++
                        code = dw ushr (32 - codelen)
                    }
                    r = dict2[(codelen - 1) * 2 + 1]
                }

                r = r - code
                if (!bits.advance(codelen)) break

                val idx = r.toInt()
                if (idx !in dictionary.indices) continue
                val (slice, isLeaf) = dictionary[idx]
                if (isLeaf) {
                    out.write(slice)
                } else {
                    unpack(BitReader(slice), out, depth + 1)
                }
            }
        }

        private fun u32LE(bytes: ByteArray, offset: Int): Long {
            return (bytes[offset].toLong() and 0xFF) or
                ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
                ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
                ((bytes[offset + 3].toLong() and 0xFF) shl 24)
        }

        private class BitReader(private val data: ByteArray) {
            private var pos = 0
            private val totalBits = data.size * 8

            fun left(): Int = totalBits - pos

            fun peek(n: Int): Long {
                var result = 0L
                for (i in 0 until n) {
                    val bitPos = pos + i
                    val bit = if (bitPos < totalBits) {
                        (data[bitPos ushr 3].toInt() ushr (7 - (bitPos and 7))) and 1
                    } else {
                        0
                    }
                    result = (result shl 1) or bit.toLong()
                }
                return result
            }

            fun advance(n: Int): Boolean {
                if (pos + n > totalBits) return false
                pos += n
                return true
            }
        }
    }
}
