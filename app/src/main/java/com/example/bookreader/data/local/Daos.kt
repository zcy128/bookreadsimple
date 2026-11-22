package com.example.bookreader.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books WHERE userId = :userId ORDER BY lastReadTime DESC, addedTime DESC")
    fun getAllBooksFlow(userId: String): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE userId = :userId ORDER BY lastReadTime DESC, addedTime DESC")
    suspend fun getAllBooks(userId: String): List<BookEntity>

    @Query("SELECT * FROM books WHERE id = :bookId AND userId = :userId")
    suspend fun getBookById(bookId: String, userId: String): BookEntity?

    @Query("SELECT * FROM books WHERE userId = :userId AND isDownloaded = 1")
    fun getDownloadedBooksFlow(userId: String): Flow<List<BookEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(book: BookEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateAll(books: List<BookEntity>)

    @Query("UPDATE books SET isDownloaded = :isDownloaded, localFilePath = :localPath WHERE id = :bookId AND userId = :userId")
    suspend fun updateDownloadStatus(bookId: String, userId: String, isDownloaded: Boolean, localPath: String?)

    @Query("UPDATE books SET progressPercent = :progress, lastReadTime = :lastReadTime WHERE id = :bookId AND userId = :userId")
    suspend fun updateProgress(bookId: String, userId: String, progress: Float, lastReadTime: Long)

    @Query("UPDATE books SET lastReadTime = :lastReadTime WHERE id = :bookId AND userId = :userId")
    suspend fun updateLastReadTime(bookId: String, userId: String, lastReadTime: Long)

    @Query("DELETE FROM books WHERE id = :bookId AND userId = :userId")
    suspend fun deleteBook(bookId: String, userId: String)
}

@Dao
interface ProgressDao {
    @Query("SELECT * FROM reading_progress WHERE bookId = :bookId AND userId = :userId")
    suspend fun getProgressForBook(bookId: String, userId: String): ProgressEntity?

    @Query("SELECT * FROM reading_progress WHERE bookId = :bookId AND userId = :userId")
    fun getProgressFlowForBook(bookId: String, userId: String): Flow<ProgressEntity?>

    @Query("SELECT * FROM reading_progress WHERE userId = :userId AND isSynced = 0")
    suspend fun getUnsyncedProgressList(userId: String): List<ProgressEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveProgress(progress: ProgressEntity)

    @Query("UPDATE reading_progress SET isSynced = 1 WHERE bookId = :bookId AND userId = :userId")
    suspend fun markSynced(bookId: String, userId: String)

    @Query("DELETE FROM reading_progress WHERE bookId = :bookId AND userId = :userId")
    suspend fun deleteProgress(bookId: String, userId: String)
}
