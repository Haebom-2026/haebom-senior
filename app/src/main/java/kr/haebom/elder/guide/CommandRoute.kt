package kr.haebom.elder.guide

import kr.haebom.elder.family.Assessment
import kr.haebom.elder.family.Level
import kr.haebom.elder.family.Sensitivity

/**
 * 화면 위에서 바로 들은 말을 어떻게 처리할지. 모두 **지금 화면 그대로** 처리함
 * (확인이 필요한 일도 해봄 앱 화면으로 넘어가지 않고 떠 있는 카드로 물음).
 * 판단 순서는 말하기 창과 같음: 사기 의심 → 도움 요청 → 비밀번호 → 금융 → 아는 일 → AI.
 */
internal sealed class CommandRoute {
    /** 아무 말도 못 들음 */
    data object Empty : CommandRoute()

    /** L3 사기 의심: 바로 보호자 알림 + 안내 멈춤 + 경고 카드 */
    data class Scam(val assessment: Assessment) : CommandRoute()

    /** 도움 요청(SOS) */
    data object Help : CommandRoute()

    /** "비밀번호 넣어 줘" 등: 안내 멈춤 + 주의 카드 */
    data object Private : CommandRoute()

    /** L2 금융: 바로 보호자 알림 + 경고 카드 (계속하기/그만두기) */
    data class Finance(val assessment: Assessment) : CommandRoute()

    /** 정부24: "본인인증에서 멈춰요" 확인 카드 뒤 안내 */
    data object Gov24 : CommandRoute()

    /** 앱이 아는 일을 지금 바로 시작 */
    data class Task(val task: GuideTask, val query: String?) : CommandRoute()

    /**
     * 어디서 할지 말하지 않은 애매한 말: 뜻을 짐작해 "○○에서 △△ 할까요?" 예/아니요로 물음.
     * 예: "임영웅 찾아줘" → 유튜브에서 '임영웅' 검색
     */
    data class Guess(val task: GuideTask, val query: String) : CommandRoute()

    /** 화면 안내 AI 로 (앱 이름이면 바로 열고, 그 밖에는 안내할지 물음) */
    data object Ai : CommandRoute()

    /** 알아듣지 못함 (AI 도 없을 때) */
    data object Unrecognized : CommandRoute()

    companion object {
        fun of(spoken: String, foreground: String?, aiAvailable: Boolean): CommandRoute {
            if (spoken.isBlank()) return Empty
            val assessment = Sensitivity.fromCommand(spoken, foreground)
            if (assessment.level == Level.L3) return Scam(assessment)
            if (GuideTask.isHelpCommand(spoken)) return Help
            if (GuideTask.isPrivateCommand(spoken)) return Private
            if (assessment.level == Level.L2) return Finance(assessment)
            val task = GuideTask.forCommand(spoken, foreground)
            return when {
                task == GuideTask.GOV24 -> Gov24
                task != null -> Task(task, if (task == GuideTask.YOUTUBE) GuideTask.youtubeQuery(spoken) else null)
                else -> guessYoutube(spoken)?.let { Guess(GuideTask.YOUTUBE, it) }
                    ?: if (aiAvailable) Ai else Unrecognized
            }
        }

        private val WATCH_VERBS = listOf("찾아", "검색", "틀어", "보여", "들려", "재생", "보고싶", "듣고싶")
        private val OTHER_PLACE = listOf("에서", "앱", "어플", "설정", "열어", "켜")

        /**
         * 앱을 말하지 않고 "○○ 찾아 줘 / 틀어 줘 / 보여 줘" 라고 하면 유튜브 검색으로 짐작.
         * "설정에서 디스플레이 찾아 줘" 처럼 다른 곳을 말했으면 짐작하지 않음.
         */
        fun guessYoutube(spoken: String): String? {
            val normalized = GuideTask.normalize(spoken)
            if (WATCH_VERBS.none { it in normalized } || OTHER_PLACE.any { it in normalized }) return null
            return GuideTask.youtubeQuery(spoken)
        }
    }
}
