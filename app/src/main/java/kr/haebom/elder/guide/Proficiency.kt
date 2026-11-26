package kr.haebom.elder.guide

/**
 * 안내 단계 (어르신이 스스로 고름). 익숙해질수록 안내를 줄여 혼자 하시게 돕습니다.
 *
 * | 단계      | 빨간 테두리                   | 이동 화살표 | 자동완성 단추 | 매 단계 읽어 주기 | 서버 AI 모드 |
 * |-----------|-------------------------------|-------------|---------------|-------------------|--------------|
 * | BEGINNER  | 바깥 어둡게 + 굵은 테두리 + 말풍선 | 있음        | 있음          | 예                | guide        |
 * | FAMILIAR  | 얇은 테두리만                  | 있음        | 있음          | 예                | guide        |
 * | EXPERT    | 없음 (자막에 짧은 힌트만)       | 없음        | 없음          | 아니요 (주의·질문만) | solo (막힐 때 힌트) |
 *
 * 주의(경고)·질문·확인 카드는 단계와 상관없이 항상 보여 주고 읽어 줍니다.
 */
internal enum class Proficiency(
    val highlight: Highlight,
    val arrows: Boolean,
    val autofill: Boolean,
    val speakSteps: Boolean,
    val aiMode: String,
) {
    BEGINNER(Highlight.FULL, arrows = true, autofill = true, speakSteps = true, aiMode = "guide"),
    FAMILIAR(Highlight.LIGHT, arrows = true, autofill = true, speakSteps = true, aiMode = "guide"),
    EXPERT(Highlight.NONE, arrows = false, autofill = false, speakSteps = false, aiMode = "solo");

    enum class Highlight { FULL, LIGHT, NONE }

    /** 한 단계 위 (이미 맨 위면 null) */
    val next: Proficiency? get() = entries.getOrNull(ordinal + 1)

    companion object {
        /** 이만큼 안내를 끝까지 마치면 한 단계 줄여 볼지 한 번 물어봄 */
        const val SUGGEST_AFTER_DONE = 5

        fun of(name: String?): Proficiency = entries.firstOrNull { it.name == name } ?: BEGINNER

        /**
         * 단계를 올려 볼지 권할 때인지. 지금 단계에서 [SUGGEST_AFTER_DONE] 번 끝까지 마쳤고,
         * 이 단계에서 아직 권한 적이 없을 때 (맨 위 단계면 권하지 않음)
         */
        fun shouldSuggest(level: Proficiency, doneAtLevel: Int, alreadySuggested: Boolean): Boolean =
            level.next != null && doneAtLevel >= SUGGEST_AFTER_DONE && !alreadySuggested
    }
}
