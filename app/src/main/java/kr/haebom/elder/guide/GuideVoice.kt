package kr.haebom.elder.guide

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

internal class GuideVoice(context: Context) {
    private val engine = TextToSpeech(context) { status ->
        if (status == TextToSpeech.SUCCESS) enableOfflineKorean()
    }
    private var ready = false
    private var pending: String? = null

    private fun enableOfflineKorean() {
        val voice = engine.voices?.firstOrNull {
            it.locale.language == Locale.KOREAN.language && !it.isNetworkConnectionRequired &&
                TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
        } ?: return

        ready = engine.setVoice(voice) == TextToSpeech.SUCCESS
        engine.setSpeechRate(0.85f)
        if (ready) pending?.let(::speak)
    }

    fun speak(message: String) {
        if (!ready) {
            pending = message
            return
        }
        pending = null
        engine.speak(message, TextToSpeech.QUEUE_FLUSH, null, "haebom-guide")
    }

    fun stop() {
        pending = null
        engine.stop()
    }

    fun close() {
        stop()
        engine.shutdown()
    }
}
