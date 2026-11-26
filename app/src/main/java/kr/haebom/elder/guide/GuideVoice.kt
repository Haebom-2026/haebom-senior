package kr.haebom.elder.guide

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

internal class GuideVoice(context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val engine = TextToSpeech(context) { status ->
        if (status == TextToSpeech.SUCCESS) enableOfflineKorean()
    }
    private var ready = false
    private var pending: Pair<String, (() -> Unit)?>? = null
    private var serial = 0
    /** 지금 읽는 문장이 끝나면 부를 일 (새 문장을 읽거나 멈추면 버림) */
    private var onDone: Pair<String, () -> Unit>? = null

    init {
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = finished(utteranceId)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finished(utteranceId)
        })
    }

    private fun finished(utteranceId: String?) {
        main.post {
            val done = onDone ?: return@post
            if (done.first != utteranceId) return@post
            onDone = null
            done.second()
        }
    }

    private fun enableOfflineKorean() {
        val voice = engine.voices?.firstOrNull {
            it.locale.language == Locale.KOREAN.language && !it.isNetworkConnectionRequired &&
                TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
        } ?: return

        ready = engine.setVoice(voice) == TextToSpeech.SUCCESS
        engine.setSpeechRate(0.85f)
        if (ready) pending?.let { (text, done) -> speak(text, done) }
    }

    /** [message] 를 읽음. [done] 은 다 읽은 뒤 메인 스레드에서 한 번 부름 (읽을 수 없으면 잠시 뒤) */
    fun speak(message: String, done: (() -> Unit)? = null) {
        if (!ready) {
            pending = message to done
            // 한국어 음성이 아직 없으면 기다리지 않고 잠시 뒤 다음 일로
            if (done != null) {
                main.postDelayed({
                    if (pending?.second === done) {
                        pending = null
                        done()
                    }
                }, NO_VOICE_DELAY_MS)
            }
            return
        }
        pending = null
        val id = "haebom-guide-${++serial}"
        onDone = done?.let { id to it }
        engine.speak(message, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    fun stop() {
        pending = null
        onDone = null
        engine.stop()
    }

    fun close() {
        stop()
        engine.shutdown()
    }

    private companion object {
        const val NO_VOICE_DELAY_MS = 1_500L
    }
}
