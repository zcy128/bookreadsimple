package com.example.bookreader.data.local

import androidx.room.Entity
import com.example.bookreader.domain.model.Book
import com.example.bookreader.domain.model.BookFormat
import com.example.bookreader.domain.model.ReadingProgress

@Entity(
    tableName = "books",
    primaryKeys = ["id", "userId"]
)
data class BookEntity(
    val id: String,
    val userId: String,
    val title: String,
    val author: String,
    val format: String,
    val remoteStoragePath: String,
    val localFilePath: String?,
    val fileSize: Long,
    val isDownloaded: Boolean,
    val coverUrl: String?,
    val addedTime: Long,
    val lastReadTime: Long,
    val progressPercent: Float,
    val titleLocked: Boolean = false,
    val titleUpdatedAt: Long = 0L,
    val passwordProtected: Boolean = false,
    val passwordUpdatedAt: Long = 0L,
    val tag: String = "",
    val tagLocked: Boolean = false,
    val tagUpdatedAt: Long = 0L
) {
    fun toDomain(): Book = Book(
        id = id,
        userId = userId,
        title = title,
        author = author,
        format = try { BookFormat.valueOf(format) } catch (e: Exception) { BookFormat.TXT },
        remoteStoragePath = remoteStoragePath,
        localFilePath = localFilePath,
        fileSize = fileSize,
        isDownloaded = isDownloaded,
        coverUrl = coverUrl,
        addedTime = addedTime,
        lastReadTime = lastReadTime,
        progressPercent = progressPercent,
        titleLocked = titleLocked,
        titleUpdatedAt = titleUpdatedAt,
        passwordProtected = passwordProtected,
        passwordUpdatedAt = passwordUpdatedAt,
        tag = tag,
        tagLocked = tagLocked,
        tagUpdatedAt = tagUpdatedAt
    )

    companion object {
        fun fromDomain(book: Book): BookEntity = BookEntity(
            id = book.id,
            userId = book.userId,
            title = book.title,
            author = book.author,
            format = book.format.name,
            remoteStoragePath = book.remoteStoragePath,
            localFilePath = book.localFilePath,
            fileSize = book.fileSize,
            isDownloaded = book.isDownloaded,
            coverUrl = book.coverUrl,
            addedTime = book.addedTime,
            lastReadTime = book.lastReadTime,
            progressPercent = book.progressPercent,
            titleLocked = book.titleLocked,
            titleUpdatedAt = book.titleUpdatedAt,
            passwordProtected = book.passwordProtected,
            passwordUpdatedAt = book.passwordUpdatedAt,
            tag = book.tag,
            tagLocked = book.tagLocked,
            tagUpdatedAt = book.tagUpdatedAt
        )
    }
}

@Entity(
    tableName = "reading_progress",
    primaryKeys = ["bookId", "userId"]
)
data class ProgressEntity(
    val bookId: String,
    val userId: String,
    val progressPercent: Float,
    val currentChapter: Int,
    val totalChapters: Int,
    val currentPage: Int,
    val totalPages: Int,
    val charOffset: Long,
    val lastReadTime: Long,
    val isSynced: Boolean
) {
    fun toDomain(): ReadingProgress = ReadingProgress(
        bookId = bookId,
        userId = userId,
        progressPercent = progressPercent,
        currentChapter = currentChapter,
        totalChapters = totalChapters,
        currentPage = currentPage,
        totalPages = totalPages,
        charOffset = charOffset,
        lastReadTime = lastReadTime,
        isSynced = isSynced
    )

    companion object {
        fun fromDomain(progress: ReadingProgress): ProgressEntity = ProgressEntity(
            bookId = progress.bookId,
            userId = progress.userId,
            progressPercent = progress.progressPercent,
            currentChapter = progress.currentChapter,
            totalChapters = progress.totalChapters,
            currentPage = progress.currentPage,
            totalPages = progress.totalPages,
            charOffset = progress.charOffset,
            lastReadTime = progress.lastReadTime,
            isSynced = progress.isSynced
        )
    }
}
