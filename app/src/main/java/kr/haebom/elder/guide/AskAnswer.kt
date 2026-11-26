package kr.haebom.elder.guide

/**
 * AI 질문(ask)의 답 단추와 말로 한 대답 맞추기 (안드로이드 없이 단위 테스트).
 * "네" → "네", "아니요" → "아니요", "몰라"/"모르겠어" → "모르겠어요", 단추 글자가 들어 있으면 그 단추.
 */
internal object AskAnswer {
    private val UNKNOWN = listOf("모르", "몰라", "글쎄")

    fun match(spoken: String, options: List<String>, unknownLabel: String): String? {
        if (options.isEmpty()) return null
        val text = GuideTask.normalize(spoken)
        if (text.isEmpty()) return null
        if (UNKNOWN.any { it in text }) return unknownLabel
        // 단추 글자를 그대로 말함 (띄어쓰기 무시)
        options.firstOrNull { GuideTask.normalize(it).let { option -> option.isNotEmpty() && (option == text || option in text) } }
            ?.let { return it }
        // 네/아니요 단추
        val yes = options.firstOrNull { GuideTask.normalize(it) in setOf("네", "예", "있어요", "맞아요", "했어요") }
        val no = options.firstOrNull { GuideTask.normalize(it).startsWith("아니") || GuideTask.normalize(it) == "없어요" }
        return when (Confirm.answer(spoken)) {
            Confirm.Answer.YES -> yes
            Confirm.Answer.NO -> no
            Confirm.Answer.OTHER -> null
        }
    }
}
