package kr.haebom.elder.guide

/**
 * 화면 아래(또는 위) 큰 자막으로 띄울 말을 모으는 곳.
 * 듣기 화면, 안내 흐름, 보호자 알림 등 어디서든 [post] 하면 접근성 서비스가 자막 막대에 보여 주고 읽어 줍니다.
 *
 * - 안내 자막(GUIDE): 지금 눌러야 할 곳. 화면이 바뀌기 전까지 계속 보임 (서비스가 직접 정함)
 * - 잠깐 자막(그 밖): 들은 말, "못 알아들었어요", "유튜브로 이동할게요" 등. 정한 시간 동안 안내 자막보다 먼저 보임
 */
internal object GuideCaption {
    enum class Tone { GUIDE, LISTEN, HEARD, INFO, WARN, DONE }

    data class Message(
        val text: String,
        val tone: Tone,
        val until: Long,
        val speak: Boolean,
        val serial: Long,
    )

    var listener: ((Message?) -> Unit)? = null
    var now: () -> Long = { android.os.SystemClock.uptimeMillis() }

    var current: Message? = null
        private set
    private var serial = 0L

    /** 잠깐 자막 띄우기. [durationMs] 가 0 이면 다음 자막이 올 때까지 */
    fun post(text: String, tone: Tone, durationMs: Long = DEFAULT_MS, speak: Boolean = tone != Tone.HEARD) {
        val until = if (durationMs <= 0) Long.MAX_VALUE else now() + durationMs
        current = Message(text, tone, until, speak, ++serial)
        listener?.invoke(current)
    }

    /** 들리는 말을 실시간으로 (소리 내어 읽지 않음, 같은 자막 칸을 계속 고쳐 씀) */
    fun heard(partial: String) {
        if (partial.isBlank()) return
        post("“$partial”", Tone.HEARD, HEARD_MS, speak = false)
    }

    fun clear() {
        current = null
        listener?.invoke(null)
    }

    /** 지금 보여야 할 잠깐 자막 (시간이 지났으면 null) */
    fun active(): Message? = current?.takeIf { it.until > now() }

    const val DEFAULT_MS = 6_000L
    const val HEARD_MS = 4_000L
}
