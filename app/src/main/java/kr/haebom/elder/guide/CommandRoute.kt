package kr.haebom.elder.guide

import kr.haebom.elder.family.Level
import kr.haebom.elder.family.Sensitivity

/**
 * 화면 위에서 바로 들은 말을 어디서 처리할지.
 * - 바로 안내할 수 있는 일(유튜브·연락처·AI 안내)은 지금 화면 그대로 시작
 * - 확인 창이 필요한 일(사기 의심 L3, 금융 L2, 도움 요청 SOS, 비밀번호, 정부24 확인)은 말하기 창에 들은 말을 넘겨 처리
 */
internal sealed class CommandRoute {
    /** 아무 말도 못 들음 */
    data object Empty : CommandRoute()

    /** 말하기 창(확인 창)에서 처리 */
    data object NeedsWindow : CommandRoute()

    /** 앱이 아는 일을 지금 바로 시작 */
    data class Task(val task: GuideTask, val query: String?) : CommandRoute()

    /** 화면 안내 AI 로 시작 */
    data object Ai : CommandRoute()

    /** 알아듣지 못함 (AI 도 없을 때) */
    data object Unrecognized : CommandRoute()

    companion object {
        fun of(spoken: String, foreground: String?, aiAvailable: Boolean): CommandRoute {
            if (spoken.isBlank()) return Empty
            val level = Sensitivity.fromCommand(spoken, foreground).level
            if (level == Level.L3 || level == Level.L2 ||
                GuideTask.isHelpCommand(spoken) || GuideTask.isPrivateCommand(spoken)
            ) {
                return NeedsWindow
            }
            val task = GuideTask.forCommand(spoken, foreground)
            return when {
                task == GuideTask.GOV24 -> NeedsWindow // "본인인증에서 멈춰요" 확인 창
                task != null -> Task(task, if (task == GuideTask.YOUTUBE) GuideTask.youtubeQuery(spoken) else null)
                aiAvailable -> Ai
                else -> Unrecognized
            }
        }
    }
}
