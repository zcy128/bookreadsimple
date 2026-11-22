package com.example.bookreader.data.remote

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import com.example.bookreader.domain.model.Book
import com.example.bookreader.domain.model.BookFormat
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

sealed class DownloadState {
    object Idle : DownloadState()
    data class Downloading(val progress: Float) : DownloadState()
    data class Completed(val file: File) : DownloadState()
    data class Error(val message: String) : DownloadState()
}

data class DriveEbook(
    val id: String,
    val name: String,
    val size: Long,
    val createdTimeMillis: Long,
    val format: BookFormat,
    val documentUri: String,
    val tag: String = ""
)

data class DriveScanResult(
    val books: List<DriveEbook>,
    val totalFilesScanned: Int,
    val unsupportedFilesCount: Int,
    val failedCount: Int,
    val unsupportedSampleNames: List<String> = emptyList()
)

class DriveFolderNotAuthorizedException :
    Exception("Google Drive folder has not been authorized")

class DriveRootNotAllowedException :
    Exception("Choose a folder inside Google Drive, not the Drive root")

data class DriveProgressRecord(
    val syncKey: String,
    val fileName: String,
    val driveFileId: String,
    val legacyBookId: String,
    val progressPercent: Float,
    val currentChapter: Int,
    val totalChapters: Int,
    val currentPage: Int,
    val totalPages: Int,
    val charOffset: Long,
    val lastReadTime: Long,
    val customTitle: String = "",
    val titleUpdatedTime: Long = 0L,
    val passwordProtected: Boolean = false,
    val passwordUpdatedTime: Long = 0L,
    val customTag: String = "",
    val tagUpdatedTime: Long = 0L,
    val priorityClearedTime: Long = 0L
)

data class DriveShelfSync(
    val entries: List<DriveProgressRecord> = emptyList(),
    val passwordSalt: String = "",
    val passwordHash: String = "",
    val passwordUpdatedTime: Long = 0L
)

/**
 * Books live in a Google Drive folder the user selects and authorizes.
 * The app does not request a hardcoded Drive OAuth scope and does not create files in Drive root.
 */
class GoogleDriveManager(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasAuthorization(userId: String): Boolean {
        val uri = folderUri(userId) ?: return false
        val persisted = context.contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == uri && permission.isReadPermission && permission.isWritePermission
        }
        if (!persisted) return false
        val folder = DocumentFile.fromTreeUri(context, uri) ?: return false
        return folder.exists() && folder.isDirectory && folder.canRead()
    }

    fun folderDisplayName(userId: String): String? {
        if (!hasAuthorization(userId)) return null
        return prefs.getString(nameKey(userId), null)
            ?: folderUri(userId)?.let { DocumentFile.fromTreeUri(context, it)?.name }
    }

    fun authorizeFolder(userId: String, treeUri: Uri): String {
        if (isDriveRoot(treeUri)) throw DriveRootNotAllowedException()
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(treeUri, flags)
        val folder = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalStateException("The selected folder cannot be opened")
        if (!folder.isDirectory) throw IllegalStateException("Select a folder, not a file")
        val name = folder.name?.ifBlank { null } ?: "Google Drive"
        prefs.edit()
            .putString(uriKey(userId), treeUri.toString())
            .putString(nameKey(userId), name)
            .apply()
        return name
    }

    fun watchFolder(userId: String, onChange: () -> Unit): () -> Unit {
        val uri = folderUri(userId) ?: return {}
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                onChange()
            }
        }
        context.contentResolver.registerContentObserver(uri, true, observer)
        return {
            context.contentResolver.unregisterContentObserver(observer)
        }
    }

    fun getLocalUserBooksDir(userId: String): File {
        return File(context.filesDir, "users/$userId/books").apply { if (!exists()) mkdirs() }
    }

    fun getLocalFileForBook(userId: String, bookId: String, format: BookFormat): File {
        return File(getLocalUserBooksDir(userId), "${cacheKey(bookId)}.${format.extension}")
    }

    suspend fun listEbooks(userId: String): List<DriveEbook> = withContext(Dispatchers.IO) {
        scanEbooks(userId).books
    }

    suspend fun scanEbooks(userId: String): DriveScanResult = withContext(Dispatchers.IO) {
        if (!hasAuthorization(userId)) throw DriveFolderNotAuthorizedException()
        val treeUri = folderUri(userId) ?: throw DriveFolderNotAuthorizedException()
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val books = mutableListOf<DriveEbook>()
        val stats = ScanStats()
        collectEbooksViaSaf(treeUri, rootDocId, "", books, mutableSetOf(), 0, stats)
        DriveScanResult(
            books = books,
            totalFilesScanned = stats.totalFiles,
            unsupportedFilesCount = stats.unsupportedFiles,
            failedCount = stats.failedCount,
            unsupportedSampleNames = stats.unsupportedSamples
        )
    }

    private class ScanStats(
        var totalFiles: Int = 0,
        var unsupportedFiles: Int = 0,
        var failedCount: Int = 0,
        val unsupportedSamples: MutableList<String> = mutableListOf()
    )

    private data class SafChild(
        val docId: String,
        val name: String,
        val mimeType: String,
        val size: Long,
        val lastModified: Long
    )

    private fun collectEbooksViaSaf(
        treeUri: Uri,
        parentDocId: String,
        tag: String,
        into: MutableList<DriveEbook>,
        seen: MutableSet<String>,
        depth: Int,
        stats: ScanStats
    ) {
        if (depth > MAX_FOLDER_DEPTH) return
        if (!seen.add(parentDocId)) return

        val children = try {
            queryFolderChildren(treeUri, parentDocId)
        } catch (_: Exception) {
            stats.failedCount++
            return
        }

        for (child in children) {
            val name = child.name.trim()
            if (name.isEmpty()) continue
            if (child.mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                val childTag = if (tag.isEmpty()) name else "$tag / $name"
                collectEbooksViaSaf(treeUri, child.docId, childTag, into, seen, depth + 1, stats)
                continue
            }
            if (name.equals(PROGRESS_FILE_NAME, ignoreCase = true)) continue
            stats.totalFiles++
            val format = BookFormat.fromSupportedFileName(name)
            if (format == null) {
                stats.unsupportedFiles++
                if (stats.unsupportedSamples.size < 3) {
                    stats.unsupportedSamples.add(name)
                }
                continue
            }
            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, child.docId)
            val id = documentId(docUri)
            if (id == null) {
                stats.failedCount++
                continue
            }
            into.add(
                DriveEbook(
                    id = id,
                    name = name,
                    size = child.size.coerceAtLeast(0L),
                    createdTimeMillis = child.lastModified.takeIf { it > 0L } ?: System.currentTimeMillis(),
                    format = format,
                    documentUri = docUri.toString(),
                    tag = tag
                )
            )
        }
    }

    private fun queryFolderChildren(treeUri: Uri, parentDocId: String): List<SafChild> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )

        repeat(2) { attempt ->
            val cursor = context.contentResolver.query(childrenUri, projection, null, null, null)
                ?: return emptyList()
            val isLoading = cursor.extras?.getBoolean(DocumentsContract.EXTRA_LOADING, false) == true
            if (isLoading && attempt == 0) {
                // Google Drive SAF returns cached results with EXTRA_LOADING=true while fetching from cloud.
                val latch = CountDownLatch(1)
                val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        latch.countDown()
                    }
                }
                try {
                    cursor.registerContentObserver(observer)
                    context.contentResolver.registerContentObserver(childrenUri, true, observer)
                    latch.await(6, TimeUnit.SECONDS)
                } catch (_: Exception) {
                } finally {
                    try {
                        cursor.unregisterContentObserver(observer)
                    } catch (_: Exception) {
                    }
                    try {
                        context.contentResolver.unregisterContentObserver(observer)
                    } catch (_: Exception) {
                    }
                    cursor.close()
                }
                return@repeat
            }

            cursor.use { c ->
                val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val modIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                val list = ArrayList<SafChild>(c.count.coerceAtLeast(0))
                while (c.moveToNext()) {
                    val docId = if (idIdx >= 0 && !c.isNull(idIdx)) c.getString(idIdx) else continue
                    val name = if (nameIdx >= 0 && !c.isNull(nameIdx)) c.getString(nameIdx) else continue
                    val mime = if (mimeIdx >= 0 && !c.isNull(mimeIdx)) c.getString(mimeIdx) else ""
                    val size = if (sizeIdx >= 0 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else 0L
                    val modified = if (modIdx >= 0 && !c.isNull(modIdx)) c.getLong(modIdx) else 0L
                    list.add(SafChild(docId, name, mime, size, modified))
                }
                return list
            }
        }
        return emptyList()
    }

    private fun findDocument(
        folder: DocumentFile,
        target: Uri,
        seen: MutableSet<String>,
        depth: Int
    ): DocumentFile? {
        if (depth > MAX_FOLDER_DEPTH) return null
        if (!seen.add(folder.uri.toString())) return null
        for (document in folder.listFiles().orEmpty()) {
            if (document.uri == target) return document
            if (document.isDirectory) {
                val nested = findDocument(document, target, seen, depth + 1)
                if (nested != null) return nested
            }
        }
        return null
    }

    suspend fun uploadEbook(userId: String, file: File, displayName: String): DriveEbook = withContext(Dispatchers.IO) {
        val folder = requireFolder(userId)
        val safeName = sanitizeFileName(displayName)
        val created = folder.createFile(mimeFor(safeName), safeName)
            ?: throw IllegalStateException("Could not create the file in the authorized Google Drive folder")
        context.contentResolver.openOutputStream(created.uri)?.use { output ->
            file.inputStream().use { input -> input.copyTo(output) }
        } ?: throw IllegalStateException("Could not write the file to Google Drive")
        val id = documentId(created.uri) ?: created.uri.toString()
        DriveEbook(
            id = id,
            name = created.name ?: safeName,
            size = created.length().takeIf { it > 0L } ?: file.length(),
            createdTimeMillis = created.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis(),
            format = BookFormat.fromSupportedFileName(safeName) ?: BookFormat.TXT,
            documentUri = created.uri.toString()
        )
    }

    suspend fun deleteEbook(userId: String, documentUri: String) = withContext(Dispatchers.IO) {
        if (!documentUri.startsWith("content:")) return@withContext
        val folder = requireFolder(userId)
        val target = Uri.parse(documentUri)
        val match = findDocument(folder, target, mutableSetOf(), 0) ?: return@withContext
        if (!match.isFile || !match.delete()) {
            throw IllegalStateException("Could not delete the file from the authorized Google Drive folder")
        }
    }

    fun downloadEbook(book: Book): Flow<DownloadState> = callbackFlow {
        val job = launch(Dispatchers.IO) {
            try {
                val source = book.remoteStoragePath
                if (!source.startsWith("content:")) {
                    trySend(DownloadState.Error("This book is not in the authorized Google Drive folder"))
                    return@launch
                }
                val target = getLocalFileForBook(book.userId, book.id, book.format)
                if (target.exists() && target.length() > 0L &&
                    (book.fileSize <= 0L || target.length() == book.fileSize)
                ) {
                    trySend(DownloadState.Completed(target))
                    return@launch
                }
                trySend(DownloadState.Downloading(0.01f))
                copyToLocal(Uri.parse(source), target) { progress ->
                    trySend(DownloadState.Downloading(progress))
                }
                trySend(DownloadState.Completed(target))
            } catch (e: Exception) {
                trySend(DownloadState.Error(e.message ?: "Download failed"))
            } finally {
                close()
            }
        }
        awaitClose { job.cancel() }
    }

    private fun requireFolder(userId: String): DocumentFile {
        if (!hasAuthorization(userId)) throw DriveFolderNotAuthorizedException()
        val uri = folderUri(userId) ?: throw DriveFolderNotAuthorizedException()
        return DocumentFile.fromTreeUri(context, uri) ?: throw DriveFolderNotAuthorizedException()
    }

    private fun folderUri(userId: String): Uri? {
        return prefs.getString(uriKey(userId), null)?.let(Uri::parse)
    }

    private fun copyToLocal(source: Uri, target: File, onProgress: (Float) -> Unit) {
        val length = querySize(source)
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, "${target.name}.part")
        context.contentResolver.openInputStream(source)?.use { input ->
            part.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var transferred = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    transferred += count
                    if (length > 0L) {
                        onProgress((transferred.toFloat() / length.toFloat()).coerceIn(0f, 1f))
                    }
                }
            }
        } ?: throw IllegalStateException("Could not read the Google Drive file")
        if (target.exists() && !target.delete()) {
            throw IllegalStateException("Could not replace the local book file")
        }
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
    }

    private fun querySize(uri: Uri): Long {
        val projection = arrayOf(DocumentsContract.Document.COLUMN_SIZE)
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            if (cursor.moveToFirst() && index >= 0 && !cursor.isNull(index)) {
                return cursor.getLong(index)
            }
        }
        return DocumentFile.fromSingleUri(context, uri)?.length() ?: 0L
    }

    private fun documentId(uri: Uri): String? {
        return try {
            DocumentsContract.getDocumentId(uri)
        } catch (e: Exception) {
            null
        }
    }

    private fun isDriveRoot(uri: Uri): Boolean {
        val docId = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (e: Exception) {
            return false
        }
        val normalized = docId.lowercase()
        return normalized == "root" ||
            normalized.endsWith(":root") ||
            normalized.endsWith("/root") ||
            normalized.endsWith("doc=root") ||
            normalized.endsWith(";root")
    }

    fun readReadingProgress(userId: String): DriveShelfSync {
        if (!hasAuthorization(userId)) return DriveShelfSync()
        val file = findProgressFile(userId) ?: return DriveShelfSync()
        val text = context.contentResolver.openInputStream(file.uri)?.use { input ->
            input.readBytes().toString(Charsets.UTF_8)
        } ?: throw IllegalStateException("Could not read reading progress from Google Drive")
        if (text.isBlank()) return DriveShelfSync()
        val root = JSONObject(text)
        val books = root.optJSONObject("books")
        val entries = mutableListOf<DriveProgressRecord>()
        if (books != null) {
            val keys = books.keys()
            while (keys.hasNext()) {
                val syncKey = keys.next()
                val item = books.optJSONObject(syncKey) ?: continue
                val fileName = item.optString("fileName", "")
                entries.add(
                    DriveProgressRecord(
                        syncKey = syncKey,
                        fileName = fileName,
                        driveFileId = item.optString("driveFileId", ""),
                        legacyBookId = item.optString("bookId", "").ifBlank {
                            if (fileName.isBlank()) syncKey else ""
                        },
                        progressPercent = item.optDouble("progressPercent", 0.0).toFloat(),
                        currentChapter = item.optInt("currentChapter", 0),
                        totalChapters = item.optInt("totalChapters", 1),
                        currentPage = item.optInt("currentPage", 1),
                        totalPages = item.optInt("totalPages", 1),
                        charOffset = item.optLong("charOffset", 0L),
                        lastReadTime = item.optLong("lastReadTime", 0L),
                        customTitle = item.optString("customTitle", ""),
                        titleUpdatedTime = item.optLong("titleUpdatedTime", 0L),
                        passwordProtected = item.optBoolean("passwordProtected", false),
                        passwordUpdatedTime = item.optLong("passwordUpdatedTime", 0L),
                        customTag = item.optString("customTag", ""),
                        tagUpdatedTime = item.optLong("tagUpdatedTime", 0L),
                        priorityClearedTime = item.optLong("priorityClearedTime", 0L)
                    )
                )
            }
        }
        return DriveShelfSync(
            entries = entries,
            passwordSalt = root.optString("passwordSalt", ""),
            passwordHash = root.optString("passwordHash", ""),
            passwordUpdatedTime = root.optLong("passwordUpdatedTime", 0L)
        )
    }

    fun writeReadingProgress(userId: String, sync: DriveShelfSync) {
        val folder = requireFolder(userId)
        val books = JSONObject()
        for (entry in sync.entries) {
            books.put(
                entry.syncKey,
                JSONObject()
                    .put("fileName", entry.fileName)
                    .put("driveFileId", entry.driveFileId)
                    .put("progressPercent", entry.progressPercent.toDouble())
                    .put("currentChapter", entry.currentChapter)
                    .put("totalChapters", entry.totalChapters)
                    .put("currentPage", entry.currentPage)
                    .put("totalPages", entry.totalPages)
                    .put("charOffset", entry.charOffset)
                    .put("lastReadTime", entry.lastReadTime)
                    .put("customTitle", entry.customTitle)
                    .put("titleUpdatedTime", entry.titleUpdatedTime)
                    .put("passwordProtected", entry.passwordProtected)
                    .put("passwordUpdatedTime", entry.passwordUpdatedTime)
                    .put("customTag", entry.customTag)
                    .put("tagUpdatedTime", entry.tagUpdatedTime)
                    .put("priorityClearedTime", entry.priorityClearedTime)
            )
        }
        val payloadJson = JSONObject().put("version", 2).put("books", books)
        if (sync.passwordHash.isNotBlank() && sync.passwordSalt.isNotBlank()) {
            payloadJson
                .put("passwordSalt", sync.passwordSalt)
                .put("passwordHash", sync.passwordHash)
                .put("passwordUpdatedTime", sync.passwordUpdatedTime)
        }
        val payload = payloadJson.toString().toByteArray(Charsets.UTF_8)
        val file = findProgressFile(userId)
            ?: folder.createFile("application/json", PROGRESS_FILE_NAME)
            ?: throw IllegalStateException("Could not create the progress file in Google Drive")
        context.contentResolver.openOutputStream(file.uri, "wt")?.use { output ->
            output.write(payload)
        } ?: throw IllegalStateException("Could not write reading progress to Google Drive")
    }

    private fun findProgressFile(userId: String): DocumentFile? {
        val folder = requireFolder(userId)
        return folder.listFiles().orEmpty().firstOrNull { document ->
            document.isFile && document.name.equals(PROGRESS_FILE_NAME, ignoreCase = true)
        }
    }

    private fun mimeFor(name: String): String {
        return when (BookFormat.fromSupportedFileName(name)) {
            BookFormat.EPUB, BookFormat.KEPUB -> "application/epub+zip"
            BookFormat.MOBI -> "application/x-mobipocket-ebook"
            BookFormat.PDF -> "application/pdf"
            BookFormat.TXT -> "text/plain"
            null -> "application/octet-stream"
        }
    }

    private fun uriKey(userId: String) = "folder_uri_$userId"

    private fun nameKey(userId: String) = "folder_name_$userId"

    companion object {
        private const val PREFS_NAME = "drive_folder_prefs"
        const val PROGRESS_FILE_NAME = "bookreader-progress.json"
        private const val MAX_FOLDER_DEPTH = 8

        fun cacheKey(bookId: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bookId.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { byte -> "%02x".format(byte) }.take(32)
        }

        fun sanitizeFileName(name: String): String {
            val cleaned = name.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().trim('.')
            return cleaned.take(180).ifEmpty { "book" }
        }

        fun progressSyncKey(fileName: String): String = fileName.trim().lowercase()

        fun driveFileId(documentId: String): String? {
            val marker = "doc="
            val index = documentId.lastIndexOf(marker)
            if (index < 0) return null
            val id = documentId.substring(index + marker.length).substringBefore(';').trim()
            if (id.length < 8 || id.equals("root", ignoreCase = true)) return null
            return id
        }

        fun titleFromFileName(name: String): String {
            var base = name.substringBeforeLast('.', name).trim()
            if (base.lowercase().endsWith(".kepub")) {
                base = base.substringBeforeLast('.').trim()
            }
            return base.ifEmpty { "Untitled" }
        }
    }
}

/**
 * System folder picker. The user chooses the Google Drive account and folder.
 */
class DriveFolderPicker : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: Context, input: Uri?): Intent {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        return super.createIntent(context, input).addFlags(flags)
    }
}
