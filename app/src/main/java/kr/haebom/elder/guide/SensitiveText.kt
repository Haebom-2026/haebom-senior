package kr.haebom.elder.guide

/**
 * 서버로 보낼 화면에서 민감한 글자 다루기 (안드로이드 없이 단위 테스트).
 *
 * 서버는 화면 글자 어디에든 '비밀번호', '주민등록번호' 같은 말이 있으면 화면 전체를 거부함.
 * 그래서 로그인 뒤 신청서처럼 안내 문구에 그 말이 들어 있기만 해도 안내가 막혔음.
 * - 입력 칸 자체나 그 바로 위·옆 글자가 민감한 말이면: 민감 정보를 넣는 칸 → [Result.blocked] (어르신이 직접, 안내 잠깐 멈춤)
 * - 입력 칸과 상관없는 안내 문구면: 그 글자만 빼고 보냄 (나머지 화면으로 안내는 계속)
 */
internal object SensitiveText {
    /** 서버 거부 규칙과 같은 말들 (haebom-server src/ai/guide.ts SECRET_FIELD) */
    private val WORDS = Regex(
        """비밀\s?번호|패스워드|password|passcode|암호|인증\s?번호|OTP|보안\s?카드|CVC|CVV|PIN\b|핀\s?번호|주민\s?(등록)?\s?번호|생년월일\s?6자리""",
        RegexOption.IGNORE_CASE,
    )

    /** 입력 칸 위로 이만큼(px) 안에 있는 글자는 그 칸의 이름표로 봄 */
    private const val LABEL_ABOVE_PX = 260
    private const val LABEL_SIDE_GAP_PX = 40

    data class Result(val nodes: List<ScreenNode>, val blocked: Boolean)

    fun isSensitive(text: String?): Boolean = text != null && WORDS.containsMatchIn(text)

    fun sanitize(nodes: List<ScreenNode>): Result {
        val sensitiveLabels = nodes.filter { !it.editable && (isSensitive(it.text) || isSensitive(it.description)) }
        val editables = nodes.filter { it.editable }
        val blocked = editables.any { field ->
            isSensitive(field.text) || isSensitive(field.description) ||
                sensitiveLabels.any { label -> labels(label, field) }
        }
        if (blocked) return Result(emptyList(), blocked = true)
        return Result(nodes.filterNot { it in sensitiveLabels }, blocked = false)
    }

    /** [label] 이 [field] 의 이름표인지: 칸 바로 위(가로로 겹침)나 같은 줄 왼쪽 */
    private fun labels(label: ScreenNode, field: ScreenNode): Boolean {
        val l = label.bounds ?: return false
        val f = field.bounds ?: return false
        val overlapsX = l[0] < f[2] && f[0] < l[2]
        val above = overlapsX && l[3] <= f[1] + 10 && f[1] - l[3] <= LABEL_ABOVE_PX
        val sameRowLeft = l[1] < f[3] && f[1] < l[3] && l[2] <= f[0] + LABEL_SIDE_GAP_PX
        val inside = l[0] >= f[0] && l[2] <= f[2] && l[1] >= f[1] && l[3] <= f[3]
        return above || sameRowLeft || inside
    }
}
