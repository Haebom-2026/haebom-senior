package kr.haebom.elder.guide

/**
 * "이렇게 할까요?" 확인에 쓰는 규칙 (안드로이드 없이 단위 테스트).
 * - 어르신의 말로 하는 대답이 예/아니요인지
 * - 말한 앱 이름이 폰에 깔린 앱 중 어느 것인지
 */
internal object Confirm {
    enum class Answer { YES, NO, OTHER }

    private val YES = listOf("네", "예", "응", "어", "그래", "좋아", "맞아", "해줘", "해주세요", "열어줘", "부탁", "오케이", "ok", "yes")
    private val NO = listOf("아니", "아뇨", "싫어", "안해", "됐어", "그만", "취소", "아냐", "no")

    /** 짧은 대답만 예/아니요로 봄. 길면 새로운 요청으로 처리 ("아니 유튜브 열어 줘" 는 새 요청) */
    fun answer(spoken: String): Answer {
        val text = GuideTask.normalize(spoken).trimEnd('.', '!', '?', '요')
        if (text.isEmpty() || text.length > 8) return Answer.OTHER
        return when {
            NO.any { text.startsWith(it) } -> Answer.NO
            YES.any { text.startsWith(it) || text == it } -> Answer.YES
            else -> Answer.OTHER
        }
    }

    /** 말한 앱 이름과 맞는 앱 (이름이 똑같은 것 먼저). [apps] 는 (앱 이름, 패키지) */
    fun findApp(spoken: String, apps: List<Pair<String, String>>): Pair<String, String>? {
        val wanted = GuideTask.normalize(spoken).removeSuffix("앱")
        return apps.firstOrNull { GuideTask.normalize(it.first) == wanted }
            ?: apps.firstOrNull { GuideTask.isSameApp(spoken, it.first) }
    }
}
