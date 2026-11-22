package com.example.bookreader.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.bookreader.data.repository.BookRepository
import com.example.bookreader.domain.model.Book
import com.example.bookreader.domain.model.BookFormat
import com.example.bookreader.domain.model.Chapter
import com.example.bookreader.domain.model.ChapterProgressBarMode
import com.example.bookreader.domain.model.ReaderSettings
import com.example.bookreader.domain.model.ReadingMode
import com.example.bookreader.domain.model.ReadingProgress
import com.example.bookreader.parser.EpubBookParser
import com.example.bookreader.parser.EpubParsedBook
import com.example.bookreader.parser.MobiBookParser
import com.example.bookreader.parser.TxtBookParser
import com.example.bookreader.parser.TxtParsedResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed class ReaderContentState {
    object Loading : ReaderContentState()
    data class TxtReady(val result: TxtParsedResult) : ReaderContentState()
    data class PdfReady(val pdfFile: File) : ReaderContentState()
    data class EpubReady(val epubBook: EpubParsedBook) : ReaderContentState()
    data class Error(val message: String) : ReaderContentState()
}

data class ReaderUiState(
    val book: Book? = null,
    val contentState: ReaderContentState = ReaderContentState.Loading,
    val progress: ReadingProgress = ReadingProgress(bookId = ""),
    val chapters: List<Chapter> = emptyList(),
    val currentChapterIndex: Int = 0,
    val initialChapterScrollFraction: Float = 0f,
    val settings: ReaderSettings = ReaderSettings(),
    val chapterProgressPercent: Float = 0f,
    val chapterPageIndex: Int = 0,
    val chapterPageCount: Int = 1,
    val chapterProgressBarMode: ChapterProgressBarMode = ChapterProgressBarMode.HIDDEN,
    val isControlsVisible: Boolean = false,
    val isTocOpen: Boolean = false,
    val isSettingsOpen: Boolean = false
)

class ReaderViewModel(
    private val bookId: String,
    private val repository: BookRepository,
    private val cacheBaseDir: File
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    private var progressSaveJob: Job? = null
    private var lastChapterChangeTime = 0L
    private val devicePreferences = ReaderDevicePreferences(repository.context)

    init {
        val pinned = devicePreferences.isProgressBarPinned()
        _uiState.value = _uiState.value.copy(
            settings = ReaderSettings(
                readingMode = devicePreferences.readingMode(),
                pageTurnHand = devicePreferences.pageTurnHand()
            ),
            chapterProgressBarMode = if (pinned) ChapterProgressBarMode.PINNED else ChapterProgressBarMode.HIDDEN
        )
        loadBookAndContent()
    }

    private fun loadBookAndContent() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(contentState = ReaderContentState.Loading)

            val currentUid = repository.currentUserId
            val book = repository.getBookById(bookId, currentUid)
            if (book == null) {
                _uiState.value = _uiState.value.copy(contentState = ReaderContentState.Error("书籍未找到"))
                return@launch
            }

            // 读取已保存进度（优先本地离线，随后对比云端），严格绑定当前用户
            val savedProgress = repository.getReadingProgress(bookId, currentUid) ?: ReadingProgress(bookId = bookId, userId = currentUid)
            _uiState.value = _uiState.value.copy(book = book, progress = savedProgress)

            // 打开书籍即标记为最近阅读，书架自动置顶
            repository.updateBookLastReadTime(bookId, currentUid)

            val file = try {
                repository.ensureOfflineCopy(book)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(contentState = ReaderContentState.Error(e.message ?: "Could not download the book"))
                return@launch
            }

            when (book.format) {
                BookFormat.TXT -> {
                    try {
                        val parsed = TxtBookParser.parse(file)
                        _uiState.value = _uiState.value.copy(
                            contentState = ReaderContentState.TxtReady(parsed),
                            chapters = parsed.chapters,
                            currentChapterIndex = savedProgress.currentChapter.coerceIn(0, (parsed.chapters.size - 1).coerceAtLeast(0))
                        )
                    } catch (e: Exception) {
                        _uiState.value = _uiState.value.copy(contentState = ReaderContentState.Error("TXT 解析异常: ${e.message}"))
                    }
                }
                BookFormat.PDF -> {
                    _uiState.value = _uiState.value.copy(
                        contentState = ReaderContentState.PdfReady(file)
                    )
                }
                BookFormat.EPUB, BookFormat.KEPUB, BookFormat.MOBI -> {
                    try {
                        val parsed = if (book.format == BookFormat.MOBI) {
                            MobiBookParser.parse(file, cacheBaseDir)
                        } else {
                            EpubBookParser.parse(file, cacheBaseDir)
                        }
                        val initialChapter = savedProgress.currentChapter.coerceIn(0, (parsed.chapters.size - 1).coerceAtLeast(0))
                        val initialFraction = (savedProgress.charOffset.toFloat() / 10000f).coerceIn(0f, 1f)
                        _uiState.value = _uiState.value.copy(
                            contentState = ReaderContentState.EpubReady(parsed),
                            chapters = parsed.chapters,
                            currentChapterIndex = initialChapter,
                            initialChapterScrollFraction = initialFraction
                        )
                    } catch (e: Exception) {
                        _uiState.value = _uiState.value.copy(contentState = ReaderContentState.Error("${book.format.displayName} 解析异常: ${e.message}"))
                    }
                }
            }
        }
    }

    fun toggleControls() {
        _uiState.value = _uiState.value.copy(
            isControlsVisible = !_uiState.value.isControlsVisible
        )
    }

    fun hideControls() {
        if (!_uiState.value.isControlsVisible) return
        _uiState.value = _uiState.value.copy(isControlsVisible = false)
    }

    fun setTocOpen(open: Boolean) {
        _uiState.value = _uiState.value.copy(isTocOpen = open)
    }

    fun setSettingsOpen(open: Boolean) {
        _uiState.value = _uiState.value.copy(isSettingsOpen = open)
    }

    fun updateSettings(newSettings: ReaderSettings) {
        _uiState.value = _uiState.value.copy(settings = newSettings)
        devicePreferences.setReadingMode(newSettings.readingMode)
        devicePreferences.setPageTurnHand(newSettings.pageTurnHand)
    }

    fun onBottomEdgeTapped() {
        val next = when (_uiState.value.chapterProgressBarMode) {
            ChapterProgressBarMode.HIDDEN -> ChapterProgressBarMode.PREVIEW
            ChapterProgressBarMode.PREVIEW -> ChapterProgressBarMode.PINNED
            ChapterProgressBarMode.PINNED -> ChapterProgressBarMode.HIDDEN
        }
        _uiState.value = _uiState.value.copy(chapterProgressBarMode = next)
        devicePreferences.setProgressBarPinned(next == ChapterProgressBarMode.PINNED)
    }

    fun selectChapter(index: Int, openAtEnd: Boolean = false) {
        val total = _uiState.value.chapters.size
        if (index in 0 until total) {
            val progressPercent = if (total > 1) index.toFloat() / (total - 1).toFloat() else 0f
            val targetChapter = _uiState.value.chapters.getOrNull(index)
            val chapterStart = targetChapter?.startCharOffset ?: 0L
            val nextChapterStart = _uiState.value.chapters.getOrNull(index + 1)?.startCharOffset
            val charOffset = if (openAtEnd) {
                ((nextChapterStart ?: Long.MAX_VALUE) - 1L).coerceAtLeast(chapterStart)
            } else {
                chapterStart
            }
            val updated = _uiState.value.progress.copy(
                currentChapter = index,
                totalChapters = total,
                progressPercent = progressPercent,
                charOffset = charOffset,
                lastReadTime = System.currentTimeMillis()
            )
            _uiState.value = _uiState.value.copy(
                currentChapterIndex = index,
                initialChapterScrollFraction = if (openAtEnd) 1f else 0f,
                chapterProgressPercent = if (openAtEnd) 1f else 0f,
                progress = updated,
                isTocOpen = false
            )
            saveProgressDebounced(updated)
        }
    }

    fun nextChapter() {
        val now = System.currentTimeMillis()
        if (now - lastChapterChangeTime < 450) return
        val total = _uiState.value.chapters.size
        val next = _uiState.value.currentChapterIndex + 1
        if (next < total) {
            lastChapterChangeTime = now
            selectChapter(next)
        }
    }

    fun previousChapter() {
        moveToPreviousChapter(openAtEnd = false)
    }

    fun previousChapterFromPageTurn() {
        moveToPreviousChapter(openAtEnd = true)
    }

    fun previousChapterFromGesture() {
        val openAtEnd = _uiState.value.settings.readingMode == ReadingMode.PAGE
        moveToPreviousChapter(openAtEnd = openAtEnd)
    }

    private fun moveToPreviousChapter(openAtEnd: Boolean) {
        val now = System.currentTimeMillis()
        if (now - lastChapterChangeTime < 450) return
        val prev = _uiState.value.currentChapterIndex - 1
        if (prev >= 0) {
            lastChapterChangeTime = now
            selectChapter(prev, openAtEnd = openAtEnd)
        }
    }

    fun onTxtProgressChanged(
        percent: Float,
        charOffset: Long,
        chapterIndex: Int,
        chapterFraction: Float,
        chapterPageIndex: Int,
        chapterPageCount: Int
    ) {
        val updated = _uiState.value.progress.copy(
            progressPercent = percent,
            charOffset = charOffset,
            currentChapter = chapterIndex,
            totalChapters = _uiState.value.chapters.size.coerceAtLeast(1),
            lastReadTime = System.currentTimeMillis()
        )
        _uiState.value = _uiState.value.copy(
            progress = updated,
            currentChapterIndex = chapterIndex,
            chapterProgressPercent = chapterFraction.coerceIn(0f, 1f),
            chapterPageIndex = chapterPageIndex.coerceAtLeast(0),
            chapterPageCount = chapterPageCount.coerceAtLeast(1)
        )
        saveProgressDebounced(updated)
    }

    fun onPdfProgressChanged(percent: Float, currentPage: Int, totalPages: Int) {
        val updated = _uiState.value.progress.copy(
            progressPercent = percent,
            currentPage = currentPage,
            totalPages = totalPages,
            lastReadTime = System.currentTimeMillis()
        )
        _uiState.value = _uiState.value.copy(
            progress = updated,
            chapterProgressPercent = percent.coerceIn(0f, 1f),
            chapterPageIndex = (currentPage - 1).coerceAtLeast(0),
            chapterPageCount = totalPages.coerceAtLeast(1)
        )
        saveProgressDebounced(updated)
    }

    fun onEpubChapterProgressChanged(percent: Float) {
        val chapterIdx = _uiState.value.currentChapterIndex
        val totalChapters = _uiState.value.chapters.size.coerceAtLeast(1)
        val overallPercent = (chapterIdx.toFloat() + percent) / totalChapters.toFloat()

        val updated = _uiState.value.progress.copy(
            progressPercent = overallPercent.coerceIn(0f, 1f),
            currentChapter = chapterIdx,
            totalChapters = totalChapters,
            charOffset = (percent.coerceIn(0f, 1f) * 10000).toLong(),
            lastReadTime = System.currentTimeMillis()
        )
        _uiState.value = _uiState.value.copy(
            progress = updated,
            chapterProgressPercent = percent.coerceIn(0f, 1f)
        )
        saveProgressDebounced(updated)
    }

    fun onChapterPagesChanged(pageIndex: Int, pageCount: Int) {
        if (pageCount <= 0) return
        val index = pageIndex.coerceIn(0, pageCount - 1)
        val state = _uiState.value
        if (state.chapterPageIndex == index && state.chapterPageCount == pageCount) return
        _uiState.value = state.copy(
            chapterPageIndex = index,
            chapterPageCount = pageCount
        )
    }

    private fun saveProgressDebounced(progress: ReadingProgress) {
        progressSaveJob?.cancel()
        progressSaveJob = viewModelScope.launch {
            delay(500)
            val synced = repository.updateReadingProgress(progress)
            if (synced && _uiState.value.progress.lastReadTime == progress.lastReadTime) {
                _uiState.value = _uiState.value.copy(
                    progress = _uiState.value.progress.copy(isSynced = true)
                )
            }
        }
    }

    class Factory(
        private val bookId: String,
        private val repository: BookRepository,
        private val cacheBaseDir: File
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ReaderViewModel(bookId, repository, cacheBaseDir) as T
        }
    }
}
