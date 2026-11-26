package kr.haebom.elder.guide

import java.util.Locale

/**
 * 로그인·본인인증 화면 안내 (앱 안에서만, 서버로 보내지 않음. 안드로이드 없이 단위 테스트).
 *
 * 어르신이 가장 많이 막히는 곳이 로그인·인증이라, 화면을 보고 다음에 할 일 하나를 짚어 드림:
 * 로그인 방법 고르기 → 아이디 같은 칸 쓰기 → 비밀번호·인증번호는 "직접 입력" → 동의 → 로그인/확인 누르기 → 폰에서 인증 마치기.
 *
 * 지키는 것
 * - 비밀번호·인증번호·계좌번호 같은 칸([ScreenNode.secret])은 자리와 "채워졌는지"만 앎. 글자는 읽지도 보내지도 않음
 * - 해봄이 대신 입력하지 않음 (칸을 짚고 "직접 써 주세요")
 * - 로그인 방법처럼 어르신만 아는 선택은 대신 고르지 않고 목록만 알려 드림
 */
internal object AuthGuide {
    enum class Kind {
        /** 로그인 방법이 여럿: [AuthStep.arg] 에 방법 이름들, 짚지 않음 */
        CHOOSE_METHOD,
        /** 로그인을 시작하는 단추 ('로그인', '간편인증' 하나뿐일 때) */
        START,
        /** 보통 입력 칸 (아이디·이름 등): 직접 써 주세요 */
        TYPE_FIELD,
        /** 비밀 칸 (비밀번호·인증번호 등): 해봄은 보지 않음, 직접 입력 */
        TYPE_SECRET,
        /** 약관 전체 동의 */
        AGREE,
        /** 다 입력함: 로그인·확인 단추 */
        SUBMIT,
        /** 다 입력한 것 같은데 단추가 아직 회색: 빈 칸 확인 */
        CHECK_FIELDS,
        /** 폰에 온 인증 요청을 마치고 '인증 완료' 누르기 */
        CONFIRM_ON_PHONE,
    }

    /** [target] 짚을 요소 (없으면 말로만), [arg] 문장에 넣을 이름 */
    data class AuthStep(val kind: Kind, val target: ScreenNode? = null, val arg: String = "")

    private val METHODS = listOf(
        "간편인증", "간편로그인", "카카오", "네이버", "pass", "패스", "토스", "삼성패스", "페이코", "kb모바일",
        "공동인증서", "금융인증서", "모바일신분증", "휴대폰인증", "휴대폰본인확인", "아이디로그인", "아이디", "비회원",
        "회원신청", "회원로그인",
    )
    /** 이런 말이 든 단추는 로그인 방법이 아님 ("공동인증서 등록 레이어 닫기" 같은 창 닫기·열기) */
    private val NOT_METHOD = listOf("닫기", "열기", "레이어", "취소", "안내", "더보기")
    private val LOGIN = listOf("로그인", "로그인하기", "본인인증", "본인확인", "인증하기", "회원신청하기")
    private val SUBMIT = listOf(
        "로그인", "로그인하기", "확인", "다음", "인증요청", "인증번호받기", "인증번호요청", "인증하기", "인증", "완료", "제출", "신청",
    )
    private val CONFIRM_DONE = listOf("인증완료", "인증을완료했어요", "완료했어요", "확인완료")
    private val WAITING = listOf("인증요청을보냈", "요청을보냈", "앱에서인증", "인증을완료한후", "인증을진행해", "알림을확인")
    private val AGREE = listOf("전체동의", "모두동의", "약관전체동의", "전체동의하기")

    /** 로그인·인증 화면이면 다음 할 일, 아니면 null */
    fun step(nodes: List<ScreenNode>): AuthStep? {
        val fields = nodes.filter { it.editable && it.bounds?.size == 4 }.sortedBy { it.bounds!![1] }
        val buttons = nodes.filter { it.clickable && name(it) != null }
        val methods = buttons.filter { b -> norm(name(b)).let { n -> METHODS.any { it in n } && NOT_METHOD.none { it in n } } }
        val waiting = nodes.any { n -> WAITING.any { it in norm(n.text) || it in norm(n.description) } }
        val isAuth = fields.any { it.secret } || methods.isNotEmpty() || waiting
        if (!isAuth) return null

        if (fields.isNotEmpty()) {
            fields.firstOrNull { !it.filled }?.let { field ->
                val kind = if (field.secret) Kind.TYPE_SECRET else Kind.TYPE_FIELD
                return AuthStep(kind, field, fieldLabel(field, nodes))
            }
            agree(buttons)?.let { return it }
            val submit = find(buttons, SUBMIT)
            return when {
                submit == null -> null
                submit.enabled -> AuthStep(Kind.SUBMIT, submit, name(submit).orEmpty())
                else -> AuthStep(Kind.CHECK_FIELDS, submit, name(submit).orEmpty())
            }
        }
        if (waiting) {
            find(buttons, CONFIRM_DONE)?.let { return AuthStep(Kind.CONFIRM_ON_PHONE, it, name(it).orEmpty()) }
            return AuthStep(Kind.CONFIRM_ON_PHONE)
        }
        agree(buttons)?.let { return it }
        // 화면 위에서 아래 순서로 (읽어 드리는 순서가 화면과 같게)
        val distinct = methods.distinctBy { norm(name(it)) }.sortedBy { it.bounds?.getOrNull(1) ?: 0 }
        if (distinct.size >= 2) {
            return AuthStep(Kind.CHOOSE_METHOD, arg = distinct.take(4).joinToString(", ") { name(it).orEmpty() })
        }
        val start = distinct.firstOrNull() ?: find(buttons, LOGIN) ?: return null
        return AuthStep(Kind.START, start, name(start).orEmpty())
    }

    private fun agree(buttons: List<ScreenNode>): AuthStep? =
        buttons.firstOrNull { b -> !b.checked && norm(name(b)).let { n -> AGREE.any { it in n } } }
            ?.let { AuthStep(Kind.AGREE, it, name(it).orEmpty()) }

    /** 단추 이름이 [words] 중 하나와 같은 것 (긴 안내 문장은 단추로 보지 않음). 앞의 말이 우선 */
    private fun find(buttons: List<ScreenNode>, words: List<String>): ScreenNode? {
        for (word in words) {
            buttons.firstOrNull { norm(name(it)) == word }?.let { return it }
        }
        return null
    }

    /** 입력 칸의 이름: 힌트 → 설명 → 칸 바로 위·왼쪽 글자 → "입력" */
    fun fieldLabel(field: ScreenNode, nodes: List<ScreenNode>): String =
        clean(field.hint) ?: clean(field.description)
            ?: nodes.firstOrNull { !it.editable && clean(it.text) != null && SensitiveText.isLabelOf(it, field) }?.let { clean(it.text) }
            ?: "입력"

    private fun name(node: ScreenNode): String? =
        clean(node.text)?.takeIf { it.length <= 20 } ?: clean(node.description)?.takeIf { it.length <= 20 }

    private fun clean(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }?.take(20)

    private fun norm(value: String?): String = value.orEmpty().lowercase(Locale.ROOT).filterNot(Char::isWhitespace)
}
