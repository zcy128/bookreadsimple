package com.example.bookreader.parser

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

class PdfBookRenderer(private val file: File) : Closeable {

    private var fileDescriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private val mutex = Mutex()

    val pageCount: Int
        get() = renderer?.pageCount ?: 0

    suspend fun init(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                fileDescriptor?.let {
                    renderer = PdfRenderer(it)
                    return@withContext true
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return@withContext false
        }
    }

    suspend fun renderPage(pageIndex: Int, targetWidth: Int = 1200): Bitmap? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val safeRenderer = renderer ?: return@withContext null
            if (pageIndex < 0 || pageIndex >= safeRenderer.pageCount) return@withContext null

            var page: PdfRenderer.Page? = null
            try {
                page = safeRenderer.openPage(pageIndex)
                val scale = targetWidth.toFloat() / page.width.toFloat()
                val targetHeight = (page.height * scale).toInt().coerceAtLeast(1)

                val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                // 填充白底
                val canvas = android.graphics.Canvas(bitmap)
                canvas.drawColor(Color.WHITE)

                val matrix = Matrix()
                matrix.postScale(scale, scale)

                page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return@withContext bitmap
            } catch (e: Exception) {
                e.printStackTrace()
                return@withContext null
            } finally {
                page?.close()
            }
        }
    }

    override fun close() {
        try {
            renderer?.close()
            fileDescriptor?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        renderer = null
        fileDescriptor = null
    }
}
