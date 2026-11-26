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

    /** 어느 앱으로 할지 말하지 않은 일 ("노래 틀어 줘", "택시 불러 줘"): 깔린 앱을 보고 하나면 바로, 여럿이면 고르게 */
    data class PickApp(val kind: AppChoice.Kind, val query: String?) : CommandRoute()

    /** "○○ 열어 줘": 그 앱만 열고 끝 (서버가 없어도 됨). 폰에 없는 앱이면 AI·"못 알아들음" 으로 */
    data class OpenApp(val app: String) : CommandRoute()

    /** 앱이 아는 일을 지금 바로 시작 */
    data class Task(val task: GuideTask, val query: String?) : CommandRoute()

    /** 인터넷 검색: 크롬으로 검색 결과를 바로 엶 */
    data class WebSearch(val web: Search.Web) : CommandRoute()

    /** 주변 장소: 지도 앱에서 내 위치 주변으로 검색 */
    data class MapSearch(val query: String) : CommandRoute()

    /**
     * 어디서 찾을지 말하지 않은 애매한 말: 가능성 높은 곳부터 고르게 함 (말로 "네"면 첫 번째).
     * 예: "임영웅 찾아줘" → [유튜브, 인터넷], "약국 찾아 줘" → [지도, 인터넷]
     */
    data class Guess(val query: String, val options: List<Search.Where>) : CommandRoute()

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
            // "근처 은행 어디야" 는 금융 요청이 아니라 장소 찾기
            Search.nearby(spoken)?.let { return MapSearch(it) }
            if (assessment.level == Level.L2) return Finance(assessment)
            // 앱을 말하지 않은 음악·택시·길찾기: 앱을 하나로 정하지 않고 깔린 앱 중에서 고르게
            AppChoice.kindOf(spoken)?.let { kind ->
                return PickApp(kind, if (kind == AppChoice.Kind.MUSIC) AppChoice.musicQuery(spoken) else null)
            }
            // 열어 달라고만 했으면 정부24·유튜브·연락처도 열기만 (그 뒤 할 일은 어르신이 정함)
            GuideTask.openOnlyApp(spoken)?.let { return OpenApp(it) }
            val task = GuideTask.forCommand(spoken, foreground)
            return when {
                task == GuideTask.GOV24 -> Gov24
                task != null -> Task(task, if (task == GuideTask.YOUTUBE) GuideTask.youtubeQuery(spoken) else null)
                else -> Search.web(spoken)?.let { WebSearch(it) }
                    ?: Search.guess(spoken)?.let { (query, options) -> Guess(query, options) }
                    ?: if (aiAvailable) Ai else Unrecognized
            }
        }
    }
}
