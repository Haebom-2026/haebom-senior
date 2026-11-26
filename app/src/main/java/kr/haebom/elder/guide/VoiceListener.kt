package kr.haebom.elder.guide

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * 지금 화면 그대로 말을 듣는 기기 내 한국어 음성 인식 (말하기 창을 열지 않음).
 * 접근성 서비스의 메인 스레드에서만 부릅니다.
 */
internal class VoiceListener(private val context: Context) {
    interface Callback {
        fun onReady()
        fun onPartial(text: String)
        fun onFinal(text: String)
        /** [error] SpeechRecognizer.ERROR_* */
        fun onFailed(error: Int)
    }

    private var recognizer: SpeechRecognizer? = null

    val isListening: Boolean get() = recognizer != null

    /** 화면 위에서 바로 들을 수 있는지 (Android 12+ 기기 내 인식, 마이크 권한) */
    fun canListenInPlace(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context) &&
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun start(callback: Callback): Boolean {
        if (!canListenInPlace() || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        stop()
        val speech = try {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } catch (_: UnsupportedOperationException) {
            return false
        }
        recognizer = speech
        speech.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (recognizer === speech) callback.onReady()
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onError(error: Int) {
                if (recognizer !== speech) return
                release()
                callback.onFailed(error)
            }

            override fun onResults(results: Bundle?) {
                if (recognizer !== speech) return
                release()
                callback.onFinal(results.firstText())
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (recognizer !== speech) return
                partialResults.firstText().takeIf { it.isNotBlank() }?.let(callback::onPartial)
            }
        })
        speech.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1),
        )
        return true
    }

    /** 듣기를 그만둠 (결과를 기다리지 않음) */
    fun stop() {
        release()
    }

    private fun release() {
        val speech = recognizer ?: return
        recognizer = null
        speech.cancel()
        speech.destroy()
    }

    private fun Bundle?.firstText(): String =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
}
