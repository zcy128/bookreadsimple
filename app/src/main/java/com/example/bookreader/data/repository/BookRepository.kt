package com.example.bookreader.data.repository

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.bookreader.data.auth.UserManager
import com.example.bookreader.data.local.AppDatabase
import com.example.bookreader.data.local.BookEntity
import com.example.bookreader.data.local.BookPasswordStore
import com.example.bookreader.data.local.ProgressEntity
import com.example.bookreader.data.local.StoredPassword
import com.example.bookreader.data.remote.DownloadState
import com.example.bookreader.data.remote.DriveEbook
import com.example.bookreader.data.remote.DriveFolderNotAuthorizedException
import com.example.bookreader.data.remote.DriveProgressRecord
import com.example.bookreader.data.remote.DriveShelfSync
import com.example.bookreader.data.remote.GoogleDriveManager
import com.example.bookreader.domain.model.Book
import com.example.bookreader.domain.model.BookFormat
import com.example.bookreader.domain.model.ReadingProgress
import com.example.bookreader.parser.BookMetadataExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class BookRepository(
    val context: Context,
    private val database: AppDatabase,
    private val driveManager: GoogleDriveManager,
    val userManager: UserManager,
    private val scope: CoroutineScope
) {
    enum class SyncMode { WAIT, SKIP_IF_BUSY }

    private val bookDao = database.bookDao()
    private val progressDao = database.progressDao()
    private val passwordStore = BookPasswordStore(context)
    private val sortPrefs = context.getSharedPreferences(SORT_PREFS, Context.MODE_PRIVATE)
    private val driveMutex = Mutex()

    val currentUserId: String
        get() = userManager.currentUser.value.userId

    val allBooks: Flow<List<Book>> = userManager.currentUser.flatMapLatest { user ->
        bookDao.getAllBooksFlow(user.userId).map { list -> list.map { it.toDomain() } }
    }

    val downloadedBooks: Flow<List<Book>> = userManager.currentUser.flatMapLatest { user ->
        bookDao.getDownloadedBooksFlow(user.userId).map { list -> list.map { it.toDomain() } }
    }

    suspend fun getBookById(bookId: String, userId: String = currentUserId): Book? = withContext(Dispatchers.IO) {
        bookDao.getBookById(bookId, userId)?.toDomain()
    }

    suspend fun getReadingProgress(bookId: String, userId: String = currentUserId): ReadingProgress? = withContext(Dispatchers.IO) {
        driveMutex.withLock {
            mergeDriveProgress(userId)
        }
        progressDao.getProgressForBook(bookId, userId)?.toDomain()
    }

    suspend fun updateReadingProgress(progress: ReadingProgress): Boolean = withContext(Dispatchers.IO) {
        val targetProgress = if (progress.userId.isEmpty()) progress.copy(userId = currentUserId) else progress
        liftPriorityClear(targetProgress.userId, targetProgress.bookId, targetProgress.lastReadTime)
        progressDao.saveProgress(ProgressEntity.fromDomain(targetProgress.copy(isSynced = false)))
        bookDao.updateProgress(
            targetProgress.bookId,
            targetProgress.userId,
            targetProgress.progressPercent,
            shelfLastReadTime(targetProgress.userId, targetProgress.bookId, targetProgress.lastReadTime)
        )
        driveMutex.withLock {
            mergeDriveProgress(targetProgress.userId)
        }
        progressDao.getProgressForBook(targetProgress.bookId, targetProgress.userId)?.isSynced == true
    }

    suspend fun updateBookLastReadTime(bookId: String, userId: String = currentUserId) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        liftPriorityClear(userId, bookId, now)
        bookDao.updateLastReadTime(bookId, userId, now)
    }

    suspend fun clearBookPriority(book: Book) = withContext(Dispatchers.IO) {
        if (book.lastReadTime <= 0L) return@withContext
        val clearedAt = maxOf(System.currentTimeMillis(), book.lastReadTime)
        sortPrefs.edit().putLong(priorityClearedKey(book.userId, book.id), clearedAt).apply()
        bookDao.updateLastReadTime(book.id, book.userId, 0L)
        if (driveManager.hasAuthorization(book.userId)) {
            try {
                driveMutex.withLock {
                    mergeDriveProgress(book.userId)
                }
            } catch (_: Exception) {
                // Local clear timestamp is saved; next sync will push it to Drive.
            }
        }
    }

    data class SyncSummary(
        val shelfTotalCount: Int,
        val totalFilesScanned: Int,
        val supportedBooksCount: Int,
        val addedCount: Int,
        val removedCount: Int,
        val unsupportedFilesCount: Int,
        val failedCount: Int,
        val unsupportedSampleNames: List<String> = emptyList(),
        val skippedBusy: Boolean = false
    )

    suspend fun syncPendingProgress(): Result<Int> = withContext(Dispatchers.IO) {
        syncBooksAndProgress(currentUserId).map { it.shelfTotalCount }
    }

    fun hasDriveAuthorization(userId: String = currentUserId): Boolean {
        return driveManager.hasAuthorization(userId)
    }

    fun driveFolderName(userId: String = currentUserId): String? {
        return driveManager.folderDisplayName(userId)
    }

    fun authorizeDriveFolder(treeUri: android.net.Uri, userId: String = currentUserId): String {
        return driveManager.authorizeFolder(userId, treeUri)
    }

    fun watchDriveFolder(userId: String = currentUserId, onChange: () -> Unit): () -> Unit {
        return driveManager.watchFolder(userId, onChange)
    }

    /**
     * The catalog is the Google Drive folder the user authorized by hand.
     * Files added there appear on the shelf. Files removed there disappear from the shelf.
     */
    suspend fun syncBooksAndProgress(
        userId: String = currentUserId,
        mode: SyncMode = SyncMode.WAIT
    ): Result<SyncSummary> = withContext(Dispatchers.IO) {
        if (userId.isEmpty() || userId.startsWith("guest_") || userId == "default_user") {
            return@withContext Result.failure(Exception("Sign in with Google before choosing a Drive folder"))
        }
        if (!driveManager.hasAuthorization(userId)) {
            return@withContext Result.failure(DriveFolderNotAuthorizedException())
        }
        if (!driveMutex.tryLock()) {
            if (mode == SyncMode.SKIP_IF_BUSY) {
                return@withContext Result.success(
                    SyncSummary(
                        shelfTotalCount = -1,
                        totalFilesScanned = 0,
                        supportedBooksCount = 0,
                        addedCount = 0,
                        removedCount = 0,
                        unsupportedFilesCount = 0,
                        failedCount = 0,
                        skippedBusy = true
                    )
                )
            }
            driveMutex.lock()
        }
        try {
            var failedCount = 0
            val localBooks = bookDao.getAllBooks(userId)
            for (local in localBooks) {
                if (!isDriveBacked(local)) {
                    try {
                        migrateLocalBookToDrive(local, userId)
                    } catch (_: Exception) {
                        failedCount++
                    }
                }
            }

            val scanResult = driveManager.scanEbooks(userId)
            val driveFiles = scanResult.books
            failedCount += scanResult.failedCount

            val driveIds = driveFiles.map { it.id }.toSet()
            val afterMigration = bookDao.getAllBooks(userId)
            val existingIds = afterMigration.map { it.id }.toSet()
            var removedCount = 0
            for (local in afterMigration) {
                if (local.id !in driveIds) {
                    try {
                        removeLocalBook(local)
                        removedCount++
                    } catch (_: Exception) {
                        failedCount++
                    }
                }
            }

            var addedCount = 0
            for (file in driveFiles) {
                try {
                    if (file.id !in existingIds) addedCount++
                    upsertDriveBook(userId, file)
                } catch (_: Exception) {
                    failedCount++
                }
            }

            try {
                mergeDriveProgress(userId)
            } catch (_: Exception) {
                failedCount++
            }

            val finalCount = bookDao.getAllBooks(userId).size
            Result.success(
                SyncSummary(
                    shelfTotalCount = finalCount,
                    totalFilesScanned = scanResult.totalFilesScanned,
                    supportedBooksCount = driveFiles.size,
                    addedCount = addedCount,
                    removedCount = removedCount,
                    unsupportedFilesCount = scanResult.unsupportedFilesCount,
                    failedCount = failedCount,
                    unsupportedSampleNames = scanResult.unsupportedSampleNames
                )
            )
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        } finally {
            driveMutex.unlock()
        }
    }

    suspend fun ensureOfflineCopy(book: Book): File = withContext(Dispatchers.IO) {
        val existing = existingLocalFile(book)
        if (existing != null) {
            bookDao.updateDownloadStatus(book.id, book.userId, true, existing.absolutePath)
            if (!book.titleLocked) {
                refreshMetadata(book, existing)
            }
            return@withContext existing
        }
        var downloaded: File? = null
        var errorMessage: String? = null
        downloadBook(book).collect { state ->
            when (state) {
                is DownloadState.Completed -> downloaded = state.file
                is DownloadState.Error -> errorMessage = state.message
                is DownloadState.Downloading, DownloadState.Idle -> Unit
            }
        }
        downloaded ?: throw IllegalStateException(errorMessage ?: "Could not download the book")
    }

    fun downloadBook(book: Book): Flow<DownloadState> = callbackFlow {
        val upstream = driveManager.downloadEbook(book)
        val job = launch(Dispatchers.IO) {
            try {
                upstream.collect { state ->
                    if (state is DownloadState.Completed) {
                        bookDao.updateDownloadStatus(book.id, book.userId, true, state.file.absolutePath)
                        refreshMetadata(book, state.file)
                    }
                    trySend(state)
                    if (state is DownloadState.Completed || state is DownloadState.Error) {
                        close()
                    }
                }
            } catch (e: Exception) {
                trySend(DownloadState.Error(e.message ?: "Download failed"))
                close()
            }
        }
        awaitClose { job.cancel() }
    }

    suspend fun importLocalBook(uri: android.net.Uri, originalFileName: String, tag: String = ""): Book = withContext(Dispatchers.IO) {
        val imported = driveMutex.withLock {
            val userId = currentUserId
            val format = BookFormat.fromSupportedFileName(originalFileName)
                ?: throw IllegalArgumentException("Only epub, kepub, mobi, pdf, and txt files can be imported")
            val tempId = "import_${System.currentTimeMillis()}"
            val tempFile = driveManager.getLocalFileForBook(userId, tempId, format)
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("Could not read the selected file")

                val metadata = BookMetadataExtractor.extract(
                    file = tempFile,
                    format = format,
                    originalFileName = originalFileName,
                    cacheBaseDir = context.cacheDir
                )
                val driveName = GoogleDriveManager.sanitizeFileName(originalFileName.ifBlank { "${metadata.title}.${format.extension}" })
                val trimmedTag = tag.trim()
                val uploaded = driveManager.uploadEbook(userId, tempFile, driveName)
                val finalFile = driveManager.getLocalFileForBook(userId, uploaded.id, format)
                if (tempFile.absolutePath != finalFile.absolutePath) {
                    finalFile.parentFile?.mkdirs()
                    tempFile.copyTo(finalFile, overwrite = true)
                    tempFile.delete()
                }

                val hasExtractedTitle = metadata.title.isNotBlank() &&
                    metadata.title != GoogleDriveManager.titleFromFileName(originalFileName)
                val newBook = Book(
                    id = uploaded.id,
                    userId = userId,
                    title = metadata.title,
                    author = metadata.author,
                    format = format,
                    remoteStoragePath = uploaded.documentUri,
                    localFilePath = finalFile.absolutePath,
                    fileSize = finalFile.length(),
                    isDownloaded = true,
                    addedTime = uploaded.createdTimeMillis,
                    titleLocked = hasExtractedTitle,
                    titleUpdatedAt = if (hasExtractedTitle) System.currentTimeMillis() else 0L,
                    tag = trimmedTag,
                    tagLocked = trimmedTag.isNotEmpty(),
                    tagUpdatedAt = if (trimmedTag.isNotEmpty()) System.currentTimeMillis() else 0L
                )
                bookDao.insertOrUpdate(BookEntity.fromDomain(newBook))
                newBook
            } catch (e: Exception) {
                if (tempFile.exists()) tempFile.delete()
                throw e
            }
        }
        if ((imported.tagLocked || imported.titleLocked) && driveManager.hasAuthorization(imported.userId)) {
            driveMutex.withLock {
                mergeDriveProgress(imported.userId)
            }
        }
        imported
    }

    suspend fun renameBook(book: Book, title: String) = withContext(Dispatchers.IO) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return@withContext
        val current = bookDao.getBookById(book.id, book.userId) ?: return@withContext
        bookDao.insertOrUpdate(
            current.copy(
                title = trimmed,
                titleLocked = true,
                titleUpdatedAt = System.currentTimeMillis()
            )
        )
        if (driveManager.hasAuthorization(book.userId)) {
            driveMutex.withLock {
                mergeDriveProgress(book.userId)
            }
        }
    }

    suspend fun updateBookTag(book: Book, tag: String) = withContext(Dispatchers.IO) {
        val current = bookDao.getBookById(book.id, book.userId) ?: return@withContext
        bookDao.insertOrUpdate(
            current.copy(
                tag = tag.trim(),
                tagLocked = true,
                tagUpdatedAt = System.currentTimeMillis()
            )
        )
        if (driveManager.hasAuthorization(book.userId)) {
            driveMutex.withLock {
                mergeDriveProgress(book.userId)
            }
        }
    }

    suspend fun setBookPasswordProtected(book: Book, protected: Boolean) = withContext(Dispatchers.IO) {
        val current = bookDao.getBookById(book.id, book.userId) ?: return@withContext
        bookDao.insertOrUpdate(
            current.copy(
                passwordProtected = protected,
                passwordUpdatedAt = System.currentTimeMillis()
            )
        )
        if (driveManager.hasAuthorization(book.userId)) {
            driveMutex.withLock {
                mergeDriveProgress(book.userId)
            }
        }
    }

    suspend fun deleteBook(book: Book) = withContext(Dispatchers.IO) {
        driveMutex.withLock {
            val fileName = runCatching {
                driveManager.listEbooks(book.userId).firstOrNull { it.id == book.id }?.name
            }.getOrNull()
            if (book.remoteStoragePath.startsWith("content:")) {
                driveManager.deleteEbook(book.userId, book.remoteStoragePath)
            }
            val entity = bookDao.getBookById(book.id, book.userId)
            if (entity != null) {
                removeLocalBook(entity, fileName)
            } else {
                bookDao.deleteBook(book.id, book.userId)
                progressDao.deleteProgress(book.id, book.userId)
                deleteLocalFiles(book.userId, book.id, book.localFilePath)
                deleteDriveProgress(book.userId, book.id, fileName, GoogleDriveManager.driveFileId(book.id))
            }
        }
    }

    suspend fun initDefaultBooksIfEmpty(userId: String = currentUserId) = withContext(Dispatchers.IO) {
        // The shelf comes from the Google Drive BookReader folder.
    }

    private suspend fun migrateLocalBookToDrive(local: BookEntity, userId: String) {
        val path = local.localFilePath
        val file = if (path.isNullOrBlank()) null else File(path)
        if (file == null || !file.exists() || file.length() <= 0L) {
            removeLocalBook(local)
            return
        }
        val format = try {
            BookFormat.valueOf(local.format)
        } catch (e: Exception) {
            BookFormat.fromFileName(file.name)
        }
        val driveName = GoogleDriveManager.sanitizeFileName("${local.title}.${format.extension}")
        val uploaded = driveManager.uploadEbook(userId, file, driveName)
        val target = driveManager.getLocalFileForBook(userId, uploaded.id, format)
        if (file.absolutePath != target.absolutePath) {
            target.parentFile?.mkdirs()
            file.copyTo(target, overwrite = true)
            file.delete()
        }
        val progress = progressDao.getProgressForBook(local.id, userId)
        bookDao.deleteBook(local.id, userId)
        progressDao.deleteProgress(local.id, userId)
        bookDao.insertOrUpdate(
            local.copy(
                id = uploaded.id,
                remoteStoragePath = uploaded.documentUri,
                localFilePath = target.absolutePath,
                isDownloaded = true,
                fileSize = if (uploaded.size > 0L) uploaded.size else target.length(),
                format = format.name
            )
        )
        if (progress != null) {
            progressDao.saveProgress(progress.copy(bookId = uploaded.id, isSynced = false))
        }
    }

    private suspend fun upsertDriveBook(userId: String, file: DriveEbook) {
        val existing = bookDao.getBookById(file.id, userId)
        val offlineFile = offlineFileFor(userId, file, existing)
        val downloaded = offlineFile != null
        if (existing == null) {
            val book = Book(
                id = file.id,
                userId = userId,
                title = GoogleDriveManager.titleFromFileName(file.name),
                format = file.format,
                remoteStoragePath = file.documentUri,
                localFilePath = offlineFile?.absolutePath,
                fileSize = file.size,
                isDownloaded = downloaded,
                addedTime = file.createdTimeMillis,
                tag = file.tag
            )
            bookDao.insertOrUpdate(BookEntity.fromDomain(book))
            return
        }
        bookDao.insertOrUpdate(
            existing.copy(
                format = file.format.name,
                remoteStoragePath = file.documentUri,
                fileSize = if (file.size > 0L) file.size else existing.fileSize,
                isDownloaded = downloaded,
                localFilePath = offlineFile?.absolutePath,
                title = if (existing.titleLocked || downloaded) {
                    existing.title
                } else {
                    GoogleDriveManager.titleFromFileName(file.name)
                },
                tag = if (existing.tagLocked) existing.tag else file.tag
            )
        )
    }

    private fun offlineFileFor(userId: String, file: DriveEbook, existing: BookEntity?): File? {
        val cached = driveManager.getLocalFileForBook(userId, file.id, file.format)
        if (cached.exists() && cached.length() > 0L) return cached
        val savedPath = existing?.localFilePath
        val saved = if (savedPath.isNullOrBlank()) null else File(savedPath)
        return saved?.takeIf { it.exists() && it.length() > 0L }
    }

    private fun existingLocalFile(book: Book): File? {
        val saved = book.localFilePath?.let { path -> File(path) }?.takeIf { it.exists() && it.length() > 0L }
        if (saved != null) return saved
        val cached = driveManager.getLocalFileForBook(book.userId, book.id, book.format)
        return cached.takeIf { it.exists() && it.length() > 0L }
    }

    private suspend fun refreshMetadata(book: Book, file: File) {
        val current = bookDao.getBookById(book.id, book.userId) ?: return
        val metadata = try {
            BookMetadataExtractor.extract(
                file = file,
                format = book.format,
                originalFileName = current.title.ifBlank { file.name },
                cacheBaseDir = context.cacheDir
            )
        } catch (e: Exception) {
            return
        }
        val extractedTitle = metadata.title.trim()
        val shouldPersistTitle = !current.titleLocked &&
            extractedTitle.isNotBlank() &&
            extractedTitle != file.nameWithoutExtension
        bookDao.insertOrUpdate(
            current.copy(
                title = if (current.titleLocked) current.title else extractedTitle.ifBlank { current.title },
                author = metadata.author.ifBlank { current.author },
                fileSize = file.length(),
                isDownloaded = true,
                localFilePath = file.absolutePath,
                titleLocked = if (shouldPersistTitle) true else current.titleLocked,
                titleUpdatedAt = if (shouldPersistTitle) System.currentTimeMillis() else current.titleUpdatedAt
            )
        )
        if (shouldPersistTitle && driveManager.hasAuthorization(current.userId)) {
            try {
                driveMutex.withLock {
                    mergeDriveProgress(current.userId)
                }
            } catch (_: Exception) {
                // Local title is already saved; next sync will push it to Drive.
            }
        }
    }

    private suspend fun removeLocalBook(book: BookEntity, knownFileName: String? = null) {
        val fileName = knownFileName ?: fileNameOf(book, driveFileNames(book.userId))
        bookDao.deleteBook(book.id, book.userId)
        progressDao.deleteProgress(book.id, book.userId)
        deleteLocalFiles(book.userId, book.id, book.localFilePath)
        try {
            deleteDriveProgress(book.userId, book.id, fileName, GoogleDriveManager.driveFileId(book.id))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun deleteLocalFiles(userId: String, bookId: String, localFilePath: String?) {
        try {
            localFilePath?.let { path ->
                val file = File(path)
                if (file.exists()) file.delete()
            }
            val part = localFilePath?.let { File("$it.part") }
            if (part != null && part.exists()) part.delete()
            val cached = driveManager.getLocalFileForBook(
                userId,
                bookId,
                BookFormat.fromFileName(localFilePath ?: bookId)
            )
            if (cached.exists()) cached.delete()
            File("${cached.absolutePath}.part").takeIf { it.exists() }?.delete()
            context.cacheDir.listFiles()?.forEach { file ->
                if (file.name.contains(bookId)) file.deleteRecursively()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun mergeDriveProgress(userId: String) {
        if (!driveManager.hasAuthorization(userId)) return
        val remoteSnapshot = try {
            driveManager.readReadingProgress(userId)
        } catch (e: Exception) {
            return
        }
        val remote = remoteSnapshot.entries
        val localBooks = bookDao.getAllBooks(userId)
        val names = driveFileNames(userId)
        val fileNames = localBooks.associate { book -> book.id to fileNameOf(book, names) }
        val merged = linkedMapOf<String, DriveProgressRecord>()
        for (record in remote) {
            val normalized = normalizeRecord(record, localBooks, fileNames)
            val current = merged[normalized.syncKey]
            merged[normalized.syncKey] = if (current == null) normalized else combineProgress(current, normalized)
        }
        val unsynced = progressDao.getUnsyncedProgressList(userId)
        for (pending in unsynced) {
            val book = localBooks.firstOrNull { it.id == pending.bookId }
            val fileName = fileNames[pending.bookId]
            val key = if (!fileName.isNullOrBlank()) GoogleDriveManager.progressSyncKey(fileName) else pending.bookId
            val incoming = DriveProgressRecord(
                syncKey = key,
                fileName = fileName.orEmpty(),
                driveFileId = book?.let { GoogleDriveManager.driveFileId(it.id) }.orEmpty(),
                legacyBookId = pending.bookId,
                progressPercent = pending.progressPercent,
                currentChapter = pending.currentChapter,
                totalChapters = pending.totalChapters,
                currentPage = pending.currentPage,
                totalPages = pending.totalPages,
                charOffset = pending.charOffset,
                lastReadTime = pending.lastReadTime
            )
            val current = merged[key]
            merged[key] = if (current == null) incoming else combineProgress(current, incoming)
        }
        for (book in localBooks) {
            if (!book.titleLocked || book.title.isBlank()) continue
            val titleUpdatedAt = if (book.titleUpdatedAt > 0L) {
                book.titleUpdatedAt
            } else {
                maxOf(book.lastReadTime, book.addedTime, 1L)
            }
            if (book.titleUpdatedAt <= 0L) {
                bookDao.insertOrUpdate(book.copy(titleUpdatedAt = titleUpdatedAt))
            }
            val fileName = fileNames[book.id]
            val key = if (!fileName.isNullOrBlank()) GoogleDriveManager.progressSyncKey(fileName) else book.id
            val incoming = DriveProgressRecord(
                syncKey = key,
                fileName = fileName.orEmpty(),
                driveFileId = GoogleDriveManager.driveFileId(book.id).orEmpty(),
                legacyBookId = book.id,
                progressPercent = book.progressPercent,
                currentChapter = 0,
                totalChapters = 1,
                currentPage = 1,
                totalPages = 1,
                charOffset = 0L,
                lastReadTime = 0L,
                customTitle = book.title,
                titleUpdatedTime = titleUpdatedAt
            )
            val current = merged[key]
            merged[key] = if (current == null) incoming else combineProgress(current, incoming)
        }
        for (book in localBooks) {
            if (book.passwordUpdatedAt <= 0L && !book.passwordProtected) continue
            val passwordUpdatedAt = if (book.passwordUpdatedAt > 0L) {
                book.passwordUpdatedAt
            } else {
                maxOf(book.lastReadTime, book.addedTime, 1L)
            }
            if (book.passwordUpdatedAt <= 0L) {
                val latest = bookDao.getBookById(book.id, userId) ?: book
                bookDao.insertOrUpdate(latest.copy(passwordUpdatedAt = passwordUpdatedAt))
            }
            val fileName = fileNames[book.id]
            val key = if (!fileName.isNullOrBlank()) GoogleDriveManager.progressSyncKey(fileName) else book.id
            val incoming = DriveProgressRecord(
                syncKey = key,
                fileName = fileName.orEmpty(),
                driveFileId = GoogleDriveManager.driveFileId(book.id).orEmpty(),
                legacyBookId = book.id,
                progressPercent = book.progressPercent,
                currentChapter = 0,
                totalChapters = 1,
                currentPage = 1,
                totalPages = 1,
                charOffset = 0L,
                lastReadTime = 0L,
                passwordProtected = book.passwordProtected,
                passwordUpdatedTime = passwordUpdatedAt
            )
            val current = merged[key]
            merged[key] = if (current == null) incoming else combineProgress(current, incoming)
        }
        for (book in localBooks) {
            val latest = bookDao.getBookById(book.id, userId) ?: continue
            if (!latest.tagLocked) continue
            val tagUpdatedAt = if (latest.tagUpdatedAt > 0L) {
                latest.tagUpdatedAt
            } else {
                maxOf(latest.lastReadTime, latest.addedTime, 1L)
            }
            if (latest.tagUpdatedAt <= 0L) {
                bookDao.insertOrUpdate(latest.copy(tagUpdatedAt = tagUpdatedAt))
            }
            val fileName = fileNames[book.id]
            val key = if (!fileName.isNullOrBlank()) GoogleDriveManager.progressSyncKey(fileName) else book.id
            val incoming = DriveProgressRecord(
                syncKey = key,
                fileName = fileName.orEmpty(),
                driveFileId = GoogleDriveManager.driveFileId(book.id).orEmpty(),
                legacyBookId = book.id,
                progressPercent = latest.progressPercent,
                currentChapter = 0,
                totalChapters = 1,
                currentPage = 1,
                totalPages = 1,
                charOffset = 0L,
                lastReadTime = 0L,
                customTag = latest.tag,
                tagUpdatedTime = tagUpdatedAt
            )
            val current = merged[key]
            merged[key] = if (current == null) incoming else combineProgress(current, incoming)
        }
        for (book in localBooks) {
            val clearedAt = priorityClearedAt(userId, book.id)
            if (clearedAt <= 0L) continue
            val fileName = fileNames[book.id]
            val key = if (!fileName.isNullOrBlank()) GoogleDriveManager.progressSyncKey(fileName) else book.id
            val incoming = DriveProgressRecord(
                syncKey = key,
                fileName = fileName.orEmpty(),
                driveFileId = GoogleDriveManager.driveFileId(book.id).orEmpty(),
                legacyBookId = book.id,
                progressPercent = book.progressPercent,
                currentChapter = 0,
                totalChapters = 1,
                currentPage = 1,
                totalPages = 1,
                charOffset = 0L,
                lastReadTime = 0L,
                priorityClearedTime = clearedAt
            )
            val current = merged[key]
            merged[key] = if (current == null) incoming else combineProgress(current, incoming)
        }
        val sharedPassword = resolveSharedPassword(
            userId,
            remoteSnapshot.passwordSalt,
            remoteSnapshot.passwordHash,
            remoteSnapshot.passwordUpdatedTime
        )
        val passwordChanged = remoteSnapshot.passwordSalt != sharedPassword.salt ||
            remoteSnapshot.passwordHash != sharedPassword.hash ||
            remoteSnapshot.passwordUpdatedTime != sharedPassword.updatedAt
        if (!sameProgressRecords(remote, merged.values) || passwordChanged) {
            try {
                driveManager.writeReadingProgress(
                    userId,
                    DriveShelfSync(
                        entries = merged.values.toList(),
                        passwordSalt = sharedPassword.salt,
                        passwordHash = sharedPassword.hash,
                        passwordUpdatedTime = sharedPassword.updatedAt
                    )
                )
            } catch (e: Exception) {
                return
            }
        }
        for (book in localBooks) {
            val remoteProgress = findProgressRecord(book, fileNames[book.id], merged.values) ?: continue
            val current = bookDao.getBookById(book.id, userId) ?: continue
            var updated = current
            if (remoteProgress.customTitle.isNotBlank() && remoteProgress.titleUpdatedTime > current.titleUpdatedAt) {
                updated = updated.copy(
                    title = remoteProgress.customTitle,
                    titleLocked = true,
                    titleUpdatedAt = remoteProgress.titleUpdatedTime
                )
            }
            if (remoteProgress.passwordUpdatedTime > current.passwordUpdatedAt) {
                updated = updated.copy(
                    passwordProtected = remoteProgress.passwordProtected,
                    passwordUpdatedAt = remoteProgress.passwordUpdatedTime
                )
            }
            if (remoteProgress.tagUpdatedTime > current.tagUpdatedAt) {
                updated = updated.copy(
                    tag = remoteProgress.customTag,
                    tagLocked = true,
                    tagUpdatedAt = remoteProgress.tagUpdatedTime
                )
            }
            if (remoteProgress.priorityClearedTime > priorityClearedAt(userId, book.id)) {
                sortPrefs.edit().putLong(priorityClearedKey(userId, book.id), remoteProgress.priorityClearedTime).apply()
            }
            val effectiveLastRead = shelfLastReadTime(userId, book.id, updated.lastReadTime)
            if (effectiveLastRead != updated.lastReadTime) {
                updated = updated.copy(lastReadTime = effectiveLastRead)
            }
            if (updated != current) {
                bookDao.insertOrUpdate(updated)
            }
            val local = progressDao.getProgressForBook(book.id, userId)
            if (remoteProgress.lastReadTime > 0L && (local == null || remoteProgress.lastReadTime > local.lastReadTime)) {
                progressDao.saveProgress(
                    ProgressEntity.fromDomain(
                        ReadingProgress(
                            bookId = book.id,
                            userId = userId,
                            progressPercent = remoteProgress.progressPercent,
                            currentChapter = remoteProgress.currentChapter,
                            totalChapters = remoteProgress.totalChapters,
                            currentPage = remoteProgress.currentPage,
                            totalPages = remoteProgress.totalPages,
                            charOffset = remoteProgress.charOffset,
                            lastReadTime = remoteProgress.lastReadTime,
                            isSynced = true
                        )
                    )
                )
                bookDao.updateProgress(
                    book.id,
                    userId,
                    remoteProgress.progressPercent,
                    shelfLastReadTime(userId, book.id, remoteProgress.lastReadTime)
                )
            }
        }
        for (pending in unsynced) {
            val book = localBooks.firstOrNull { it.id == pending.bookId }
            val winner = if (book == null) {
                merged[pending.bookId]
            } else {
                findProgressRecord(book, fileNames[book.id], merged.values)
            }
            if (winner != null && winner.lastReadTime >= pending.lastReadTime) {
                progressDao.markSynced(pending.bookId, userId)
            }
        }
    }

    private fun combineProgress(existing: DriveProgressRecord, incoming: DriveProgressRecord): DriveProgressRecord {
        val progress = if (incoming.lastReadTime >= existing.lastReadTime) incoming else existing
        val titleSource = when {
            incoming.titleUpdatedTime > existing.titleUpdatedTime -> incoming
            existing.titleUpdatedTime > incoming.titleUpdatedTime -> existing
            incoming.customTitle.isNotBlank() -> incoming
            else -> existing
        }
        val protectionSource = when {
            incoming.passwordUpdatedTime > existing.passwordUpdatedTime -> incoming
            existing.passwordUpdatedTime > incoming.passwordUpdatedTime -> existing
            incoming.passwordUpdatedTime > 0L -> incoming
            else -> existing
        }
        val tagSource = when {
            incoming.tagUpdatedTime > existing.tagUpdatedTime -> incoming
            existing.tagUpdatedTime > incoming.tagUpdatedTime -> existing
            incoming.tagUpdatedTime > 0L -> incoming
            else -> existing
        }
        return progress.copy(
            syncKey = progress.syncKey.ifBlank { incoming.syncKey.ifBlank { existing.syncKey } },
            fileName = progress.fileName.ifBlank { existing.fileName.ifBlank { incoming.fileName } },
            driveFileId = progress.driveFileId.ifBlank { existing.driveFileId.ifBlank { incoming.driveFileId } },
            legacyBookId = progress.legacyBookId.ifBlank { existing.legacyBookId.ifBlank { incoming.legacyBookId } },
            customTitle = titleSource.customTitle,
            titleUpdatedTime = titleSource.titleUpdatedTime,
            passwordProtected = protectionSource.passwordProtected,
            passwordUpdatedTime = protectionSource.passwordUpdatedTime,
            customTag = tagSource.customTag,
            tagUpdatedTime = tagSource.tagUpdatedTime,
            priorityClearedTime = maxOf(existing.priorityClearedTime, incoming.priorityClearedTime)
        )
    }

    private fun resolveSharedPassword(
        userId: String,
        remoteSalt: String,
        remoteHash: String,
        remoteUpdatedAt: Long
    ): StoredPassword {
        val local = passwordStore.read(userId)
        val localUpdatedAt = local?.updatedAt ?: 0L
        val remoteReady = remoteSalt.isNotBlank() && remoteHash.isNotBlank() && remoteUpdatedAt > 0L
        if (local != null && localUpdatedAt > remoteUpdatedAt) {
            return local
        }
        if (remoteReady && remoteUpdatedAt > localUpdatedAt) {
            passwordStore.saveVerifier(userId, remoteSalt, remoteHash, remoteUpdatedAt)
            return StoredPassword(remoteSalt, remoteHash, remoteUpdatedAt)
        }
        if (local != null && localUpdatedAt == 0L && !remoteReady) {
            val stamped = System.currentTimeMillis()
            passwordStore.saveVerifier(userId, local.salt, local.hash, stamped)
            return StoredPassword(local.salt, local.hash, stamped)
        }
        if (remoteReady) return StoredPassword(remoteSalt, remoteHash, remoteUpdatedAt)
        if (local != null) return local
        return StoredPassword("", "", 0L)
    }

    private fun normalizeRecord(
        record: DriveProgressRecord,
        localBooks: List<BookEntity>,
        fileNames: Map<String, String?>
    ): DriveProgressRecord {
        val recordDriveId = recordDriveId(record)
        val localBook = localBooks.firstOrNull { book ->
            book.id == record.syncKey ||
                book.id == record.legacyBookId ||
                (recordDriveId.isNotBlank() && GoogleDriveManager.driveFileId(book.id) == recordDriveId) ||
                (record.fileName.isNotBlank() && fileNames[book.id]?.equals(record.fileName, ignoreCase = true) == true)
        }
        val fileName = record.fileName.ifBlank { localBook?.let { fileNames[it.id] }.orEmpty() }
        val driveFileId = recordDriveId.ifBlank {
            localBook?.let { GoogleDriveManager.driveFileId(it.id) }.orEmpty()
        }
        val key = if (fileName.isNotBlank()) GoogleDriveManager.progressSyncKey(fileName) else record.syncKey
        return record.copy(syncKey = key, fileName = fileName, driveFileId = driveFileId)
    }

    private fun findProgressRecord(
        book: BookEntity,
        fileName: String?,
        records: Collection<DriveProgressRecord>
    ): DriveProgressRecord? {
        val nameKey = fileName?.let { GoogleDriveManager.progressSyncKey(it) }.orEmpty()
        val driveId = GoogleDriveManager.driveFileId(book.id)
        return records.filter { record ->
            (nameKey.isNotEmpty() && record.syncKey.equals(nameKey, ignoreCase = true)) ||
                (nameKey.isNotEmpty() && record.fileName.equals(fileName, ignoreCase = true)) ||
                (driveId != null && recordDriveId(record) == driveId) ||
                record.syncKey == book.id ||
                record.legacyBookId == book.id
        }.fold(null as DriveProgressRecord?) { acc, record ->
            if (acc == null) record else combineProgress(acc, record)
        }
    }

    private fun recordDriveId(record: DriveProgressRecord): String {
        if (record.driveFileId.isNotBlank()) return record.driveFileId
        return GoogleDriveManager.driveFileId(record.syncKey)
            ?: GoogleDriveManager.driveFileId(record.legacyBookId)
            ?: ""
    }

    private fun sameProgressRecords(
        remote: List<DriveProgressRecord>,
        merged: Collection<DriveProgressRecord>
    ): Boolean {
        if (remote.size != merged.size) return false
        val byKey = remote.associateBy { it.syncKey }
        return merged.all { item ->
            val old = byKey[item.syncKey] ?: return false
            old.fileName == item.fileName &&
                old.driveFileId == item.driveFileId &&
                old.progressPercent == item.progressPercent &&
                old.currentChapter == item.currentChapter &&
                old.totalChapters == item.totalChapters &&
                old.currentPage == item.currentPage &&
                old.totalPages == item.totalPages &&
                old.charOffset == item.charOffset &&
                old.lastReadTime == item.lastReadTime &&
                old.customTitle == item.customTitle &&
                old.titleUpdatedTime == item.titleUpdatedTime &&
                old.passwordProtected == item.passwordProtected &&
                old.passwordUpdatedTime == item.passwordUpdatedTime &&
                old.customTag == item.customTag &&
                old.tagUpdatedTime == item.tagUpdatedTime &&
                old.priorityClearedTime == item.priorityClearedTime
        }
    }

    private suspend fun driveFileNames(userId: String): Map<String, String> {
        return try {
            driveManager.listEbooks(userId).associate { it.id to it.name }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun fileNameOf(book: BookEntity, names: Map<String, String>): String? {
        val listed = names[book.id]
        if (!listed.isNullOrBlank()) return listed
        val path = book.remoteStoragePath
        if (path.startsWith("content:")) {
            val name = try {
                DocumentFile.fromSingleUri(context, Uri.parse(path))?.name
            } catch (e: Exception) {
                null
            }
            if (!name.isNullOrBlank()) return name
        }
        return null
    }

    private fun deleteDriveProgress(userId: String, bookId: String, fileName: String?, driveFileId: String?) {
        if (!driveManager.hasAuthorization(userId)) return
        val remote = try {
            driveManager.readReadingProgress(userId)
        } catch (e: Exception) {
            return
        }
        val nameKey = fileName?.let { GoogleDriveManager.progressSyncKey(it) }.orEmpty()
        val kept = remote.entries.filterNot { record ->
            record.legacyBookId == bookId ||
                record.syncKey == bookId ||
                (nameKey.isNotEmpty() && record.syncKey.equals(nameKey, ignoreCase = true)) ||
                (nameKey.isNotEmpty() && record.fileName.equals(fileName, ignoreCase = true)) ||
                (!driveFileId.isNullOrBlank() && record.driveFileId == driveFileId)
        }
        if (kept.size == remote.entries.size) return
        driveManager.writeReadingProgress(userId, remote.copy(entries = kept))
    }

    private fun isDriveBacked(book: BookEntity): Boolean {
        return book.remoteStoragePath.startsWith("content:")
    }

    private fun priorityClearedKey(userId: String, bookId: String) = "cleared_${userId}_$bookId"

    private fun priorityClearedAt(userId: String, bookId: String): Long {
        return sortPrefs.getLong(priorityClearedKey(userId, bookId), 0L)
    }

    private fun shelfLastReadTime(userId: String, bookId: String, candidate: Long): Long {
        val clearedAt = priorityClearedAt(userId, bookId)
        return if (clearedAt > 0L && candidate <= clearedAt) 0L else candidate
    }

    private fun liftPriorityClear(userId: String, bookId: String, readTime: Long) {
        val clearedAt = priorityClearedAt(userId, bookId)
        if (clearedAt > 0L && readTime > clearedAt) {
            sortPrefs.edit().remove(priorityClearedKey(userId, bookId)).apply()
        }
    }

    companion object {
        private const val SORT_PREFS = "shelf_sort_prefs"
    }
}
