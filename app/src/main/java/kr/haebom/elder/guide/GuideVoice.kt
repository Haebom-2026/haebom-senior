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
            override fun onStart(utteranceId: String?) {
                android.util.Log.d("HaebomGuide", "TTS 시작 $utteranceId")
            }
            override fun onDone(utteranceId: String?) {
                android.util.Log.d("HaebomGuide", "TTS 끝 $utteranceId")
                finished(utteranceId)
            }
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

    /**
     * 오프라인 한국어 음성을 먼저 고르고, 없으면 기기 기본 엔진의 한국어로 읽음.
     * (예전에는 오프라인 음성이 없으면 아예 읽지 않아서, 질문을 읽은 뒤 듣기로 넘어가지 못했음)
     */
    private fun enableOfflineKorean() {
        val offline = runCatching {
            engine.voices?.firstOrNull {
                it.locale.language == Locale.KOREAN.language && !it.isNetworkConnectionRequired &&
                    TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
            }
        }.getOrNull()
        ready = if (offline != null) {
            engine.setVoice(offline) == TextToSpeech.SUCCESS
        } else {
            engine.setLanguage(Locale.KOREAN) >= TextToSpeech.LANG_AVAILABLE
        }
        android.util.Log.i("HaebomGuide", "TTS 준비: ready=$ready offline=${offline != null}")
        engine.setSpeechRate(0.85f)
        if (ready) main.post { pending?.let { (text, done) -> speak(text, done) } }
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
        android.util.Log.d("HaebomGuide", "TTS 읽기 $id (끝나면 할 일: ${done != null})")
        // 끝나면 할 일이 있는 문장(예: AI 질문 → 듣기)을 다른 문장이 끊으면, 그 문장이 끝난 뒤에 이어서 함
        onDone = done?.let { id to it } ?: onDone?.let { id to it.second }
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
