package com.example.bookreader.domain.model

enum class BookFormat(val extension: String, val displayName: String) {
    EPUB("epub", "EPUB"),
    KEPUB("kepub", "KEPUB"),
    MOBI("mobi", "MOBI"),
    PDF("pdf", "PDF"),
    TXT("txt", "TXT");

    companion object {
        fun fromFileName(fileName: String): BookFormat {
            return fromSupportedFileName(fileName) ?: TXT
        }

        fun fromSupportedFileName(fileName: String): BookFormat? {
            val lower = fileName.lowercase()
            if (lower.endsWith(".kepub") || lower.endsWith(".kepub.epub")) return KEPUB
            return when (lower.substringAfterLast('.', "")) {
                "epub" -> EPUB
                "mobi" -> MOBI
                "pdf" -> PDF
                "txt" -> TXT
                else -> null
            }
        }
    }
}

data class Book(
    val id: String,
    val userId: String,
    val title: String,
    val author: String = "未知作者",
    val format: BookFormat,
    val remoteStoragePath: String = "",
    val localFilePath: String? = null,
    val fileSize: Long = 0L,
    val isDownloaded: Boolean = false,
    val coverUrl: String? = null,
    val addedTime: Long = System.currentTimeMillis(),
    val lastReadTime: Long = 0L,
    val progressPercent: Float = 0f,
    val titleLocked: Boolean = false,
    val titleUpdatedAt: Long = 0L,
    val passwordProtected: Boolean = false,
    val passwordUpdatedAt: Long = 0L,
    val tag: String = "",
    val tagLocked: Boolean = false,
    val tagUpdatedAt: Long = 0L
)

data class ReadingProgress(
    val bookId: String,
    val userId: String = "",
    val progressPercent: Float = 0f,
    val currentChapter: Int = 0,
    val totalChapters: Int = 1,
    val currentPage: Int = 1,
    val totalPages: Int = 1,
    val charOffset: Long = 0L,
    val lastReadTime: Long = System.currentTimeMillis(),
    val isSynced: Boolean = false
)

data class Chapter(
    val index: Int,
    val title: String,
    val contentPath: String = "",
    val startCharOffset: Long = 0L
)

enum class ReaderBgTheme(val displayName: String, val bgHex: Long, val textHex: Long) {
    LIGHT("经典白", 0xFFFAFAFA, 0xFF212121),
    PARCHMENT("羊皮纸", 0xFFF4EFE6, 0xFF241C14),
    EYE_PROTECT("护眼绿", 0xFFCCE8CF, 0xFF1B3B2B),
    DARK("深邃黑", 0xFF121212, 0xFFE0E0E0)
}

enum class ReadingMode {
    SCROLL,
    PAGE
}

enum class PageTurnHand {
    RIGHT,
    LEFT;

    /** True when the tap should move forward. Null when the tap is in the center. */
    fun movesForward(x: Float, width: Float): Boolean? {
        val onLeft = x < width * 0.35f
        val onRight = x >= width * 0.65f
        if (!onLeft && !onRight) return null
        val onHoldingSide = if (this == LEFT) onLeft else onRight
        return onHoldingSide
    }
}

enum class ChapterProgressBarMode {
    HIDDEN,
    PREVIEW,
    PINNED
}

data class ReaderSettings(
    val fontSizeSp: Int = 18,
    val lineSpacingMultiplier: Float = 1.4f,
    val bgTheme: ReaderBgTheme = ReaderBgTheme.PARCHMENT,
    val twoPageModeForTablet: Boolean = true,
    val readingMode: ReadingMode = ReadingMode.SCROLL,
    val pageTurnHand: PageTurnHand = PageTurnHand.RIGHT
)
