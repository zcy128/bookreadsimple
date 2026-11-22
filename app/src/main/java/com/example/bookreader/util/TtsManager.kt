package com.example.bookreader.util

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

class TtsManager(context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var isInitialized = false

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isInitialized = true
        }
    }

    fun speak(text: String, isEnglish: Boolean) {
        val engine = tts ?: return
        if (!isInitialized) return

        val locale = if (isEnglish) Locale.US else Locale.CHINESE
        engine.language = locale
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "dictionary_tts")
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }
}
