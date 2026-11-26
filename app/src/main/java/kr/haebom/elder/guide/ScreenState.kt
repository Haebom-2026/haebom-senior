package kr.haebom.elder.guide

/**
 * 화면 상태 요약 (AI 요청의 slots "state" 로 보냄, 100자 이내). 안드로이드 없이 단위 테스트.
 *
 * 서버 프롬프트는 노드의 상태 중 clickable·editable·scrollable·checked·selected 만 읽으므로,
 * 그 밖에 앱이 알 수 있는 상태(로그인 여부, 입력 중인 칸, 누를 수 없는 단추, 키보드, 방금 인증을 마침)를
 * 한 줄로 묶어 함께 보냄.
 */
internal object ScreenState {
    private const val MAX = 100

    enum class Login { IN, OUT, UNKNOWN }

    fun login(nodes: List<ScreenNode>): Login {
        val labels = nodes.flatMap { listOfNotNull(it.text, it.description) }.map(GuideTask::normalize)
        return when {
            labels.any { "로그아웃" in it || it == "logout" } -> Login.IN
            labels.any { (it == "로그인" || it == "login" || it == "로그인하기") } -> Login.OUT
            else -> Login.UNKNOWN
        }
    }

    /**
     * 예: "로그인됨 · 방금 본인인증 마침 · 키보드 열림 · 입력 중: 이름 · 못 누르는 단추: 다음"
     * 알 수 있는 것이 없으면 빈 문자열.
     */
    fun summary(nodes: List<ScreenNode>, keyboardOpen: Boolean, justAuthenticated: Boolean): String {
        val parts = mutableListOf<String>()
        when (login(nodes)) {
            Login.IN -> parts += "로그인됨"
            Login.OUT -> parts += "로그인 안 됨"
            Login.UNKNOWN -> Unit
        }
        if (justAuthenticated) parts += "방금 비밀번호·본인인증을 마침"
        if (keyboardOpen) parts += "키보드 열림"
        nodes.firstOrNull { it.editable && it.focused }?.let { field ->
            (field.description ?: field.hint)?.trim()?.takeIf { it.isNotEmpty() }?.let { parts += "입력 중: ${it.take(15)}" }
                ?: run { parts += "입력 칸 선택됨" }
        }
        val disabled = nodes.filter { !it.enabled && it.clickable }
            .mapNotNull { (it.text ?: it.description)?.trim()?.takeIf { label -> label.isNotEmpty() }?.take(10) }
            .distinct().take(3)
        if (disabled.isNotEmpty()) parts += "못 누르는 단추: ${disabled.joinToString(", ")}"
        return parts.joinToString(" · ").take(MAX)
    }
}
