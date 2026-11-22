package com.example.bookreader.ui.bookshelf

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.bookreader.data.auth.UserProfile
import com.example.bookreader.data.local.BookPasswordStore
import com.example.bookreader.data.remote.DownloadState
import com.example.bookreader.data.remote.DriveFolderNotAuthorizedException
import com.example.bookreader.data.remote.DriveRootNotAllowedException
import com.example.bookreader.data.repository.BookRepository
import com.example.bookreader.domain.model.Book
import com.example.bookreader.domain.model.BookFormat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class BookshelfUiState(
    val currentUser: UserProfile = UserProfile("", "加载中..."),
    val books: List<Book> = emptyList(),
    val searchQuery: String = "",
    val selectedFormat: BookFormat? = null,
    val onlyDownloaded: Boolean = false,
    val isListView: Boolean = true,
    val downloadProgressMap: Map<String, Float> = emptyMap(),
    val isSyncing: Boolean = false,
    val syncMessage: String? = null,
    val lastSyncSummary: String? = null,
    val needsDriveAuthorization: Boolean = true,
    val driveFolderName: String? = null
)

class BookshelfViewModel(private val repository: BookRepository) : ViewModel() {

    private val userManager = repository.userManager
    private val passwordStore = BookPasswordStore(repository.context)
    private val prefs = repository.context.getSharedPreferences("bookshelf_prefs", android.content.Context.MODE_PRIVATE)

    private val _searchQuery = MutableStateFlow("")
    private val _selectedFormat = MutableStateFlow<BookFormat?>(null)
    private val _onlyDownloaded = MutableStateFlow(false)
    private val _isListView = MutableStateFlow(prefs.getBoolean("is_list_view", true))
    private val _downloadProgressMap = MutableStateFlow<Map<String, Float>>(emptyMap())
    private val _isSyncing = MutableStateFlow(false)
    private val _syncMessage = MutableStateFlow<String?>(null)
    private val _lastSyncSummary = MutableStateFlow<String?>(null)
    private val _needsDriveAuthorization = MutableStateFlow(false)
    private val _driveFolderName = MutableStateFlow<String?>(null)

    private data class FilterState(
        val query: String,
        val format: BookFormat?,
        val onlyDownloaded: Boolean
    )

    private val filterStateFlow = combine(_searchQuery, _selectedFormat, _onlyDownloaded) { query, format, onlyDownloaded ->
        FilterState(query, format, onlyDownloaded)
    }

    private val displayBooksFlow = combine(repository.allBooks, filterStateFlow) { allBooks, filter ->
        // 默认排序方式：最近阅读的书在最上边 (lastReadTime DESC)，未阅读的按添加时间排序 (addedTime DESC)
        allBooks.filter { book ->
            val matchQuery = filter.query.isEmpty() ||
                book.title.contains(filter.query, ignoreCase = true) ||
                book.author.contains(filter.query, ignoreCase = true) ||
                book.tag.contains(filter.query, ignoreCase = true)
            val matchFormat = filter.format == null || book.format == filter.format
            val matchDownloaded = !filter.onlyDownloaded || book.isDownloaded
            matchQuery && matchFormat && matchDownloaded
        }.sortedWith(
            compareByDescending<Book> { it.lastReadTime }
                .thenByDescending { it.addedTime }
        )
    }

    private data class ChromeState(
        val isListView: Boolean,
        val syncing: Boolean,
        val needsDriveAuthorization: Boolean,
        val driveFolderName: String?,
        val syncMessage: String?,
        val lastSyncSummary: String?
    )

    private val syncFeedbackFlow = combine(_syncMessage, _lastSyncSummary) { msg, summary ->
        msg to summary
    }

    private val uiModeFlow = combine(
        _isListView,
        _isSyncing,
        _needsDriveAuthorization,
        _driveFolderName,
        syncFeedbackFlow
    ) { isListView, syncing, needsAuth, folderName, feedback ->
        ChromeState(isListView, syncing, needsAuth, folderName, feedback.first, feedback.second)
    }

    val uiState: StateFlow<BookshelfUiState> = combine(
        userManager.currentUser,
        displayBooksFlow,
        filterStateFlow,
        _downloadProgressMap,
        uiModeFlow
    ) { user, sortedAndFiltered, filter, progressMap, uiMode ->
        BookshelfUiState(
            currentUser = user,
            books = sortedAndFiltered,
            searchQuery = filter.query,
            selectedFormat = filter.format,
            onlyDownloaded = filter.onlyDownloaded,
            isListView = uiMode.isListView,
            downloadProgressMap = progressMap,
            isSyncing = uiMode.syncing,
            syncMessage = uiMode.syncMessage,
            lastSyncSummary = uiMode.lastSyncSummary,
            needsDriveAuthorization = uiMode.needsDriveAuthorization,
            driveFolderName = uiMode.driveFolderName
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BookshelfUiState())

    private enum class ScanMode { AUTOMATIC, RESUME, MANUAL }

    private var lastAutomaticScanAt = 0L
    private var lastResumeScanAt = 0L
    private var scannedUserId = ""
    private var hasCompletedScan = false
    private var stopWatchingDrive: (() -> Unit)? = null

    init {
        viewModelScope.launch {
            userManager.currentUser.collect { user ->
                stopWatchingDrive?.invoke()
                stopWatchingDrive = null
                if (user.isLoggedIn && user.userId.isNotEmpty() && !user.userId.startsWith("guest_")) {
                    val hasAuth = refreshDriveAuthorization(user.userId)
                    if (hasAuth) {
                        stopWatchingDrive = repository.watchDriveFolder(user.userId) {
                            requestDriveScan(ScanMode.AUTOMATIC)
                        }
                    }
                    requestDriveScan(ScanMode.RESUME)
                }
            }
        }
        viewModelScope.launch {
            while (isActive) {
                delay(45_000)
                requestDriveScan(ScanMode.AUTOMATIC)
            }
        }
    }

    override fun onCleared() {
        stopWatchingDrive?.invoke()
        super.onCleared()
    }

    fun authorizeDriveFolder(uri: Uri) {
        viewModelScope.launch {
            try {
                val name = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    repository.authorizeDriveFolder(uri)
                }
                val userId = userManager.currentUser.value.userId
                refreshDriveAuthorization(userId)
                stopWatchingDrive?.invoke()
                stopWatchingDrive = repository.watchDriveFolder(userId) {
                    requestDriveScan(ScanMode.AUTOMATIC)
                }
                _syncMessage.value = "已授权 Google Drive 文件夹：$name"
                requestDriveScan(ScanMode.MANUAL)
            } catch (e: DriveRootNotAllowedException) {
                _syncMessage.value = "请在 Google Drive 中新建或选择一个子文件夹，不要选择网盘根目录"
            } catch (e: Exception) {
                _syncMessage.value = e.localizedMessage ?: "Google Drive 授权失败"
            }
        }
    }

    private suspend fun refreshDriveAuthorization(userId: String): Boolean {
        val (hasAuth, folderName) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            repository.hasDriveAuthorization(userId) to repository.driveFolderName(userId)
        }
        _needsDriveAuthorization.value = !hasAuth
        _driveFolderName.value = folderName
        return hasAuth
    }

    fun onAppResumed() {
        requestDriveScan(ScanMode.RESUME)
    }

    private fun requestDriveScan(mode: ScanMode) {
        val user = userManager.currentUser.value
        if (!user.isLoggedIn || user.userId.isEmpty() || user.userId.startsWith("guest_")) {
            if (mode == ScanMode.MANUAL) {
                _syncMessage.value = "请先使用 Google 账号登录，再手动授权 Google Drive 文件夹"
            }
            return
        }
        val now = System.currentTimeMillis()
        if (mode == ScanMode.AUTOMATIC && now - lastAutomaticScanAt < 20_000) return
        if (mode == ScanMode.RESUME && now - lastResumeScanAt < 5_000) return
        if (mode == ScanMode.AUTOMATIC) lastAutomaticScanAt = now
        if (mode == ScanMode.RESUME) lastResumeScanAt = now

        val silent = mode != ScanMode.MANUAL
        val showSpinner = mode == ScanMode.MANUAL || !hasCompletedScan
        if (silent && _isSyncing.value) return
        if (showSpinner) _isSyncing.value = true

        viewModelScope.launch {
            if (!refreshDriveAuthorization(user.userId)) {
                if (mode == ScanMode.MANUAL) {
                    _syncMessage.value = "请先手动选择并授权 Google Drive 里的电子书文件夹"
                }
                if (showSpinner) _isSyncing.value = false
                return@launch
            }
            if (user.userId != scannedUserId) {
                scannedUserId = user.userId
                hasCompletedScan = false
            }
            val folder = _driveFolderName.value ?: "Google Drive"
            try {
                val result = repository.syncBooksAndProgress(
                    user.userId,
                    if (silent) BookRepository.SyncMode.SKIP_IF_BUSY else BookRepository.SyncMode.WAIT
                )
                if (result.isSuccess) {
                    val summary = result.getOrNull()
                    if (summary != null && !summary.skippedBusy) {
                        hasCompletedScan = true
                        if (mode == ScanMode.MANUAL) {
                            val text = buildSyncSummaryText(folder, summary)
                            _lastSyncSummary.value = text
                            _syncMessage.value = text
                        }
                    }
                } else {
                    val error = result.exceptionOrNull()
                    if (error is DriveFolderNotAuthorizedException) {
                        refreshDriveAuthorization(user.userId)
                    }
                    if (mode == ScanMode.MANUAL) {
                        val errText = if (error is DriveFolderNotAuthorizedException) {
                            "请先手动选择并授权 Google Drive 里的电子书文件夹"
                        } else {
                            "扫描失败：${error?.localizedMessage ?: "未知错误"}"
                        }
                        _lastSyncSummary.value = errText
                        _syncMessage.value = errText
                    }
                }
            } catch (e: Exception) {
                if (mode == ScanMode.MANUAL) {
                    val errText = "扫描失败：${e.message}"
                    _lastSyncSummary.value = errText
                    _syncMessage.value = errText
                }
            } finally {
                if (showSpinner) _isSyncing.value = false
            }
        }
    }

    private fun buildSyncSummaryText(folder: String, summary: BookRepository.SyncSummary): String {
        val base = "同步完成（$folder）：共扫描 ${summary.totalFilesScanned} 个文件，支持 ${summary.supportedBooksCount} 本（新增 ${summary.addedCount}，移除 ${summary.removedCount}），不支持 ${summary.unsupportedFilesCount} 个，失败 ${summary.failedCount} 个"
        return if (summary.unsupportedSampleNames.isNotEmpty()) {
            "$base；跳过：${summary.unsupportedSampleNames.joinToString(", ")}"
        } else {
            base
        }
    }

    fun toggleViewMode() {
        val newVal = !_isListView.value
        _isListView.value = newVal
        prefs.edit().putBoolean("is_list_view", newVal).apply()
    }

    fun recordBookAccess(book: Book) {
        viewModelScope.launch {
            repository.updateBookLastReadTime(book.id, book.userId)
        }
    }

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    fun onFormatFilterSelected(format: BookFormat?) {
        _selectedFormat.value = if (_selectedFormat.value == format) null else format
    }

    fun toggleOnlyDownloaded(enable: Boolean) {
        _onlyDownloaded.value = enable
    }

    fun downloadBook(book: Book) {
        if (book.isDownloaded) return
        viewModelScope.launch {
            repository.downloadBook(book).collect { state ->
                when (state) {
                    is DownloadState.Downloading -> {
                        _downloadProgressMap.value = _downloadProgressMap.value.toMutableMap().apply {
                            put(book.id, state.progress)
                        }
                    }
                    is DownloadState.Completed -> {
                        _downloadProgressMap.value = _downloadProgressMap.value.toMutableMap().apply {
                            remove(book.id)
                        }
                    }
                    is DownloadState.Error -> {
                        _downloadProgressMap.value = _downloadProgressMap.value.toMutableMap().apply {
                            remove(book.id)
                        }
                        _syncMessage.value = "下载失败: ${state.message}"
                    }
                    else -> {}
                }
            }
        }
    }

    fun importBook(uri: Uri, fileName: String, tag: String) {
        viewModelScope.launch {
            try {
                val book = repository.importLocalBook(uri, fileName, tag)
                _syncMessage.value = "已添加《${book.title}》到已授权的 Google Drive 文件夹"
            } catch (e: Exception) {
                _syncMessage.value = "导入失败: ${e.message}"
            }
        }
    }

    fun updateBookTag(book: Book, tag: String) {
        viewModelScope.launch {
            repository.updateBookTag(book, tag)
        }
    }

    fun hasBookPassword(): Boolean = passwordStore.hasPassword(repository.currentUserId)

    fun verifyBookPassword(password: String): Boolean {
        return passwordStore.verify(repository.currentUserId, password)
    }

    fun renameBook(book: Book, title: String) {
        viewModelScope.launch {
            repository.renameBook(book, title)
        }
    }

    fun protectBook(book: Book) {
        viewModelScope.launch {
            repository.setBookPasswordProtected(book, true)
        }
    }

    fun setSharedPasswordAndProtect(book: Book, password: String) {
        passwordStore.setPassword(repository.currentUserId, password)
        viewModelScope.launch {
            repository.setBookPasswordProtected(book, true)
        }
    }

    fun unprotectBook(book: Book) {
        viewModelScope.launch {
            repository.setBookPasswordProtected(book, false)
        }
    }

    fun clearBookPriority(book: Book) {
        viewModelScope.launch {
            repository.clearBookPriority(book)
        }
    }

    fun deleteBook(book: Book) {
        viewModelScope.launch {
            try {
                repository.deleteBook(book)
                _syncMessage.value = "已从书架删除《${book.title}》"
            } catch (e: Exception) {
                _syncMessage.value = "删除失败: ${e.message}"
            }
        }
    }

    fun syncCloudProgress() {
        requestDriveScan(ScanMode.MANUAL)
    }

    fun signInWithGoogle(account: com.google.android.gms.auth.api.signin.GoogleSignInAccount) {
        _isSyncing.value = true
        val result = userManager.signInWithGoogle(account)
        _isSyncing.value = false
        if (result.isSuccess) {
            val profile = result.getOrNull()
            _syncMessage.value = "登录成功：${profile?.displayName ?: "Google 读者"}。请手动授权 Google Drive 文件夹"
            requestDriveScan(ScanMode.MANUAL)
        } else {
            _syncMessage.value = "登录失败: ${result.exceptionOrNull()?.localizedMessage ?: "未知错误"}"
        }
    }

    fun postMessage(msg: String) {
        _syncMessage.value = msg
    }

    fun signOutGoogle(context: android.content.Context) {
        userManager.signOut(context)
        _syncMessage.value = "已退出 Google 登录，当前处于本地访客状态"
    }

    fun getGoogleSignInClient(context: android.content.Context) = userManager.getGoogleSignInClient(context)

    fun trySilentSignIn(context: android.content.Context) = userManager.trySilentSignIn(context)

    fun clearSyncMessage() {
        _syncMessage.value = null
    }

    fun clearLastSyncSummary() {
        _lastSyncSummary.value = null
    }

    class Factory(private val repository: BookRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return BookshelfViewModel(repository) as T
        }
    }
}
