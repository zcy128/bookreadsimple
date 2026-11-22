package com.example.bookreader.parser

import com.example.bookreader.domain.model.BookFormat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

class MobiBookParserTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `BookFormat recognizes mobi extension`() {
        assertEquals(BookFormat.MOBI, BookFormat.fromSupportedFileName("novel.mobi"))
        assertEquals(BookFormat.MOBI, BookFormat.fromSupportedFileName("NOVEL.MOBI"))
    }

    @Test
    fun `MobiBookParser parses uncompressed mobi with EXTH metadata and pagebreak`() = runBlocking {
        val html = """
            <html><body>
            <h1>第一章 开始</h1><p>第一段内容。</p>
            <mbp:pagebreak/>
            <h1>第二章 结束</h1><p>第二段内容。</p>
            </body></html>
        """.trimIndent()
        val mobiFile = buildMinimalMobiFile(
            fileName = "sample.mobi",
            title = "测试书名",
            author = "测试作者",
            htmlBytes = html.toByteArray(Charsets.UTF_8)
        )
        val outDir = tempFolder.newFolder("out")

        val parsed = MobiBookParser.parse(mobiFile, outDir)

        assertEquals("测试书名", parsed.title)
        assertEquals("测试作者", parsed.author)
        assertEquals(2, parsed.chapters.size)
        assertEquals("第一章 开始", parsed.chapters[0].title)
        assertEquals("第二章 结束", parsed.chapters[1].title)
        assertTrue(File(parsed.extractedDir, parsed.chapters[0].contentPath).readText().contains("第一段内容。"))
        assertTrue(File(parsed.extractedDir, parsed.chapters[1].contentPath).readText().contains("第二段内容。"))
    }

    private fun buildMinimalMobiFile(
        fileName: String,
        title: String,
        author: String,
        htmlBytes: ByteArray
    ): File {
        val titleBytes = title.toByteArray(Charsets.UTF_8)
        val authorBytes = author.toByteArray(Charsets.UTF_8)

        // EXTH header: "EXTH" + length + count(2) + author(100) + title(503)
        val exthOut = ByteArrayOutputStream()
        val exthBody = ByteArrayOutputStream().apply {
            writeU32BE(100)
            writeU32BE(8 + authorBytes.size)
            write(authorBytes)
            writeU32BE(503)
            writeU32BE(8 + titleBytes.size)
            write(titleBytes)
        }.toByteArray()
        exthOut.write("EXTH".toByteArray(Charsets.US_ASCII))
        exthOut.writeU32BE(12 + exthBody.size)
        exthOut.writeU32BE(2)
        exthOut.write(exthBody)
        val exthBytes = exthOut.toByteArray()

        // Record 0: PalmDOC (16) + MOBI (232) + EXTH + FullName
        val mobiHeaderLen = 232
        val fullNameOffset = 16 + mobiHeaderLen + exthBytes.size
        val rec0 = ByteArrayOutputStream()
        // PalmDOC header (16 bytes)
        rec0.writeU16BE(1) // compression = NONE
        rec0.writeU16BE(0)
        rec0.writeU32BE(htmlBytes.size)
        rec0.writeU16BE(1) // 1 text record
        rec0.writeU16BE(4096)
        rec0.writeU16BE(0) // encryption = 0
        rec0.writeU16BE(0)

        // MOBI header (232 bytes)
        val mobiHeader = ByteArray(mobiHeaderLen)
        "MOBI".toByteArray(Charsets.US_ASCII).copyInto(mobiHeader, 0)
        putU32BE(mobiHeader, 4, mobiHeaderLen)
        putU32BE(mobiHeader, 8, 2) // Book
        putU32BE(mobiHeader, 12, 65001) // UTF-8
        putU32BE(mobiHeader, 68, fullNameOffset)
        putU32BE(mobiHeader, 72, titleBytes.size)
        putU32BE(mobiHeader, 92, 2) // firstImageIndex = 2 (none)
        putU32BE(mobiHeader, 112, 0x40) // exthFlags: has EXTH
        rec0.write(mobiHeader)
        rec0.write(exthBytes)
        rec0.write(titleBytes)
        val rec0Bytes = rec0.toByteArray()

        // PDB Header (78 bytes) + 2 record entries (16 bytes)
        val dataStart = 78 + 2 * 8
        val rec0Offset = dataStart
        val rec1Offset = dataStart + rec0Bytes.size

        val pdb = ByteArrayOutputStream()
        val nameBuf = ByteArray(32)
        titleBytes.copyInto(nameBuf, 0, 0, titleBytes.size.coerceAtMost(31))
        pdb.write(nameBuf)
        pdb.write(ByteArray(44)) // attrs..uniqueIdSeed
        pdb.writeU16BE(2) // numRecords

        // Record 0 entry
        pdb.writeU32BE(rec0Offset)
        pdb.write(ByteArray(4))
        // Record 1 entry
        pdb.writeU32BE(rec1Offset)
        pdb.write(ByteArray(4))

        pdb.write(rec0Bytes)
        pdb.write(htmlBytes)

        val file = tempFolder.newFile(fileName)
        file.writeBytes(pdb.toByteArray())
        return file
    }

    private fun ByteArrayOutputStream.writeU16BE(v: Int) {
        write((v ushr 8) and 0xFF)
        write(v and 0xFF)
    }

    private fun ByteArrayOutputStream.writeU32BE(v: Int) {
        write((v ushr 24) and 0xFF)
        write((v ushr 16) and 0xFF)
        write((v ushr 8) and 0xFF)
        write(v and 0xFF)
    }

    private fun putU32BE(arr: ByteArray, offset: Int, v: Int) {
        arr[offset] = ((v ushr 24) and 0xFF).toByte()
        arr[offset + 1] = ((v ushr 16) and 0xFF).toByte()
        arr[offset + 2] = ((v ushr 8) and 0xFF).toByte()
        arr[offset + 3] = (v and 0xFF).toByte()
    }
}
