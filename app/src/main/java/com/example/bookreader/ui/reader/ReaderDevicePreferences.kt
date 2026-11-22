package com.example.bookreader.ui.reader

import android.content.Context
import com.example.bookreader.domain.model.PageTurnHand
import com.example.bookreader.domain.model.ReadingMode

/**
 * Device-local reader preferences. They are not uploaded, so the same account
 * can pin the progress bar on a tablet and keep it hidden on a phone.
 */
class ReaderDevicePreferences(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun readingMode(): ReadingMode {
        val stored = prefs.getString(KEY_READING_MODE, ReadingMode.SCROLL.name)
        return try {
            ReadingMode.valueOf(stored ?: ReadingMode.SCROLL.name)
        } catch (e: Exception) {
            ReadingMode.SCROLL
        }
    }

    fun setReadingMode(mode: ReadingMode) {
        prefs.edit().putString(KEY_READING_MODE, mode.name).apply()
    }

    fun pageTurnHand(): PageTurnHand {
        val stored = prefs.getString(KEY_PAGE_TURN_HAND, PageTurnHand.RIGHT.name)
        return try {
            PageTurnHand.valueOf(stored ?: PageTurnHand.RIGHT.name)
        } catch (e: Exception) {
            PageTurnHand.RIGHT
        }
    }

    fun setPageTurnHand(hand: PageTurnHand) {
        prefs.edit().putString(KEY_PAGE_TURN_HAND, hand.name).apply()
    }

    fun isProgressBarPinned(): Boolean = prefs.getBoolean(KEY_PROGRESS_PINNED, false)

    fun setProgressBarPinned(pinned: Boolean) {
        prefs.edit().putBoolean(KEY_PROGRESS_PINNED, pinned).apply()
    }

    companion object {
        private const val PREFS_NAME = "reader_device_prefs"
        private const val KEY_READING_MODE = "reading_mode"
        private const val KEY_PAGE_TURN_HAND = "page_turn_hand"
        private const val KEY_PROGRESS_PINNED = "chapter_progress_pinned"
    }
}
