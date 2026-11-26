package kr.haebom.elder.family

import java.util.Locale

/**
 * 민감도 단계 (서버 기획 #6·#17 의 level 과 같은 값).
 *
 * | 단계 | 예                                         | 앱 동작                                            |
 * |------|--------------------------------------------|----------------------------------------------------|
 * | L0   | 유튜브, 연락처                              | 그대로 안내                                        |
 * | L1   | 정부24·홈택스·등본·세금·연금·본인인증       | "보호자에게 알릴까요?" 물어보고 예일 때만 알림     |
 * | L2   | 은행·증권 앱, 송금·이체·계좌·잔액·대출·결제 | 경고 + 동의 없이 바로 보호자 알림 + 계속 진행 가능 |
 * | L3   | 안전계좌, 원격제어 앱, 기관 사칭 + 송금     | 바로 보호자 알림 + 안내 멈춤 + 보호자 연락 권함    |
 *
 * 규칙은 최소선입니다. 서버 AI(#17) 결과는 이보다 낮출 수 없고 올리기만 합니다 (최종 = max(규칙, AI)).
 */
internal enum class Level(val wire: String) {
    L0("L0"), L1("L1"), L2("L2"), L3("L3");

    val askFirst: Boolean get() = this == L1
    val alertNow: Boolean get() = this == L2 || this == L3
    val stopGuide: Boolean get() = this == L3
}

internal data class Assessment(
    val level: Level,
    /** none / public / identity / finance / scam */
    val category: String,
    /** 무엇 때문에 이 단계인지 (서버 reason 필드, 사람이 읽는 짧은 말) */
    val reason: String,
) {
    companion object {
        val NONE = Assessment(Level.L0, "none", "")
    }
}

internal object Sensitivity {
    const val PUBLIC = "public"
    const val IDENTITY = "identity"
    const val FINANCE = "finance"
    const val SCAM = "scam"

    /** "페이지", "페이스북" 이 "페이" 로 잡히지 않게 먼저 지움 */
    private val NOT_SENSITIVE = listOf("페이지", "페이스")

    private val SCAM_WORDS = listOf(
        "안전계좌", "대포통장", "원격제어", "원격조종", "팀뷰어", "애니데스크", "알서포트", "상품권번호", "기프트카드번호",
    )
    private val IMPERSONATION = listOf("경찰", "검찰", "검사", "수사관", "금감원", "금융감독원")
    private val MONEY = listOf("돈", "송금", "이체", "계좌", "현금", "인출")

    private val FINANCE_WORDS = listOf(
        "은행", "뱅크", "뱅킹", "송금", "이체", "계좌", "돈보내", "잔액", "잔고", "통장", "대출",
        "카드", "결제", "페이", "증권", "주식", "펀드", "코인", "토스", "카카오뱅크",
    )
    private val PUBLIC_WORDS = listOf(
        "정부24", "정부이십사", "등본", "초본", "민원", "주민센터", "가족관계", "세금", "연말정산",
        "종합소득세", "홈택스", "손택스", "연금", "건강보험", "보험료",
    )
    private val IDENTITY_WORDS = listOf("본인인증", "인증서", "신분증", "공동인증", "간편인증")

    /** 어르신이 말한(또는 입력한) 명령의 단계. 화면 앱이 더 높으면 그 단계를 씀 */
    fun fromCommand(command: String, foregroundPackage: String? = null): Assessment {
        var text = command.lowercase(Locale.ROOT).filterNot(Char::isWhitespace)
        NOT_SENSITIVE.forEach { text = text.replace(it, "") }
        val byWords = when {
            SCAM_WORDS.any { it in text } -> Assessment(Level.L3, SCAM, "사기 의심 말: ${SCAM_WORDS.first { it in text }}")
            IMPERSONATION.any { it in text } && MONEY.any { it in text } ->
                Assessment(Level.L3, SCAM, "기관 사칭 의심: ${IMPERSONATION.first { it in text }} + ${MONEY.first { it in text }}")
            FINANCE_WORDS.any { it in text } -> Assessment(Level.L2, FINANCE, "금융 요청: ${FINANCE_WORDS.first { it in text }}")
            IDENTITY_WORDS.any { it in text } -> Assessment(Level.L1, IDENTITY, "본인 증명 요청: ${IDENTITY_WORDS.first { it in text }}")
            PUBLIC_WORDS.any { it in text } -> Assessment(Level.L1, PUBLIC, "공공 민원 요청: ${PUBLIC_WORDS.first { it in text }}")
            else -> Assessment.NONE
        }
        val byApp = fromApp(foregroundPackage)
        return if (byApp.level > byWords.level) byApp else byWords
    }

    /** 들어간 앱의 단계 (패키지 이름으로만 판단) */
    fun fromApp(packageName: String?): Assessment = when (SensitiveApps.category(packageName)) {
        SensitiveApps.CATEGORY_FINANCE -> Assessment(Level.L2, FINANCE, "금융 앱 사용")
        SensitiveApps.CATEGORY_PUBLIC -> Assessment(Level.L1, PUBLIC, "공공 민원 앱 사용")
        else -> Assessment.NONE
    }

    /** 말에 나온 은행·금융 앱을 실제 패키지로 (계속 진행할 때 열어 줄 앱) */
    fun financeAppFor(command: String): String? {
        val text = command.lowercase(Locale.ROOT).filterNot(Char::isWhitespace)
        return FINANCE_APPS.entries.firstOrNull { (word, _) -> word in text }?.value
    }

    private val FINANCE_APPS = linkedMapOf(
        "카카오뱅크" to "com.kakaobank.channel",
        "토스" to "viva.republica.toss",
        "국민" to "com.kbstar.kbbank",
        "kb" to "com.kbstar.kbbank",
        "신한" to "com.shinhan.sbanking",
        "우리" to "com.wooribank.smart.npib",
        "하나" to "com.kebhana.hanapush",
        "농협" to "nh.smart.banking",
        "기업은행" to "com.ibk.android.ionebank",
        "케이뱅크" to "com.kbankwith.smartbank",
    )
}
