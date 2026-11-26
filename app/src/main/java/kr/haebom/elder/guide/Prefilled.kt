package kr.haebom.elder.guide

import java.util.Locale

/**
 * 입력 칸이 정말 채워졌는지 (안드로이드 없이 단위 테스트).
 *
 * PASS·토스 인증 같은 웹 화면은 회색 예시 글자("홍길동", "YYYYMMDD", "예) 010-1234-5678")를 힌트가 아니라
 * 칸의 글자로 넣어 두는 일이 많아, 그대로 보면 "어르신이 이미 입력함"으로 잘못 알게 됨.
 * 1. [looksLikeExample]: 예시·안내 글자로 보이면 빈 칸으로 봄
 * 2. 그래도 화면에 처음 들어왔을 때 채워진 칸이 있으면 어르신께 "직접 쓰신 건가요?" 물음 ([filledOnArrival]).
 *    "아니요" 면 그 칸([fieldKey])은 빈 칸으로 보고 안내 ([markNotMine])
 */
internal object Prefilled {
    private val EXAMPLE_START = listOf("예)", "예:", "예시", "ex)", "ex.", "e.g", "(예", "eg)")
    private val INSTRUCTION_END = listOf("입력", "입력하세요", "입력해주세요", "입력해주십시오", "자리", "선택", "선택하세요")
    private val SAMPLE_NAMES = setOf("홍길동", "김해봄", "이름")
    private val DATE_PATTERN = Regex("""(?i)y{2,4}[./-]?m{1,2}[./-]?d{1,2}|m{2}d{2}""")
    /** 0·x·* 만으로 된 번호 모양 (000-0000-0000, xxxxxx, ******) */
    private val BLANK_NUMBER = Regex("""^[0xX*\-. ]{4,}$""")

    /** 칸의 글자가 어르신이 쓴 값이 아니라 예시·안내 글자로 보이는지 (비밀번호 칸의 ●●● 에는 쓰지 않음) */
    fun looksLikeExample(text: String?, hint: String?): Boolean {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return false
        val n = t.lowercase(Locale.ROOT).filterNot(Char::isWhitespace)
        val h = hint?.trim()?.lowercase(Locale.ROOT)?.filterNot(Char::isWhitespace)
        return (h != null && h.isNotEmpty() && n == h) ||
            EXAMPLE_START.any { n.startsWith(it) } ||
            INSTRUCTION_END.any { n.endsWith(it) } ||
            n in SAMPLE_NAMES ||
            DATE_PATTERN.containsMatchIn(n) ||
            BLANK_NUMBER.matches(t)
    }

    /**
     * 이 화면의 입력 칸 묶음 (같은 신청서인지): 앱 + 칸 이름들.
     * 칸이 둘 이상인 신청서·로그인 화면만 (검색창 하나는 빈 문자열: 예전 검색어는 따로 다룸)
     */
    fun formKey(packageName: String, nodes: List<ScreenNode>): String {
        val labels = nodes.filter { it.editable }.map { AuthGuide.fieldLabel(it, nodes) }.sorted()
        return if (labels.size < 2) "" else packageName + "|" + labels.joinToString("|")
    }

    /** 칸 하나의 열쇠: 앱 + 칸 이름 */
    fun fieldKey(packageName: String, field: ScreenNode, nodes: List<ScreenNode>): String =
        packageName + "|" + AuthGuide.fieldLabel(field, nodes)

    /** 화면에 처음 들어왔을 때 이미 채워진 칸 (지금 쓰고 있는 칸은 뺌) */
    fun filledOnArrival(nodes: List<ScreenNode>): List<ScreenNode> = nodes.filter { it.editable && it.filled && !it.focused }

    /** 어르신이 "원래 있던 글자" 라고 한 칸은 빈 칸으로 (글자도 보내지 않음) */
    fun markNotMine(packageName: String, nodes: List<ScreenNode>, notMine: Set<String>): List<ScreenNode> {
        if (notMine.isEmpty()) return nodes
        return nodes.map { node ->
            if (node.editable && node.filled && fieldKey(packageName, node, nodes) in notMine) node.copy(filled = false, text = null) else node
        }
    }
}
