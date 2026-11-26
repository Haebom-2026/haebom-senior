package kr.haebom.elder.family

import java.util.Locale

/**
 * links/{elderId_guardianId} 한 쌍. 호칭은 방향이 정해진 두 필드만 씁니다 (보호자 앱과 통일).
 * - elderLabel: 보호자 앱에서 어르신을 부르는 호칭 (예: 어머니)
 * - guardianLabel: 고령자 앱에서 보호자를 부르는 호칭 (예: 큰딸). 고령자 앱 설정에서 바꿀 수 있음
 * 예전 relationship 필드는 고령자 앱이 읽거나 쓰지 않습니다.
 */
internal data class FamilyLink(
    val elderId: String,
    val guardianId: String,
    val elderName: String,
    val elderLabel: String?,
    val guardianLabel: String?,
    val connectedAt: Long,
    val guardianName: String? = null,
) {
    val id: String
        get() = "${elderId}_$guardianId"

    /** 고령자 앱 화면·음성에 쓰는 보호자 호칭: guardianLabel → guardianName → "보호자" */
    val labelForElder: String
        get() = guardianLabel.clean() ?: guardianName.clean() ?: DEFAULT_GUARDIAN_LABEL

    /** 보호자 앱 화면에 쓰는 어르신 호칭: elderLabel → elderName */
    val labelForGuardian: String
        get() = elderLabel.clean() ?: elderName

    companion object {
        const val DEFAULT_GUARDIAN_LABEL = "보호자"

        private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
    }
}

/** 6자리 일회용 코드 (연결 코드, 해제 확인 코드 모두 같은 모양) */
internal data class ConnectionCode(val code: String, val expiresAt: Long)

/** 민감 앱 이용 알림 (서버 #6 sensitive-entries, type = sensitive_entry) */
internal data class SensitiveNotice(
    val id: String,
    val elderId: String,
    val guardianIds: Set<String>,
    val appName: String,
    val packageName: String,
    val category: String,
    val createdAt: Long,
    val level: String = "L1",
    val reason: String = "",
)

/** #6 응답의 result 값과 같음 */
internal enum class NoticeResult { CREATED, DUPLICATE_SUPPRESSED, NO_GUARDIANS, FAILED }

internal data class NoticeOutcome(val result: NoticeResult, val guardianLabels: List<String> = emptyList())

internal enum class DisconnectResult { DISCONNECTED, WRONG_CODE, NOT_LINKED, FAILED }

/**
 * 고령자 앱이 보호자 연동에 쓰는 창구는 이것 하나입니다.
 * 구현은 실제 서버([ServerFamilyGateway]) 하나입니다.
 * 결과는 모두 메인 스레드에서 콜백으로 돌려줍니다.
 */
internal interface FamilyGateway {
    /** 화면에 보여 줄 연결 방식 이름 (예: "실제 서버") */
    val backendName: String

    /**
     * 고령자 쪽 해제에 보호자 앱의 해제 확인 코드가 필요한지.
     * 서버 #12 는 아직 코드를 받지 않아 서버 연결 중에는 false (서버에 요청해 둔 상태)
     */
    val requiresReleaseCode: Boolean get() = true

    /** 서버에서 목록을 다시 받아 옴 (연결 변경 FCM 을 받았을 때, 화면에 들어왔을 때) */
    fun refresh() = Unit

    /** 연결 목록·코드가 바뀔 때마다 부름 (처음 한 번 바로 부름) */
    fun observe(listener: () -> Unit): AutoCloseable

    /** 지금 연결된 보호자들 (#18) */
    fun links(): List<FamilyLink>

    /** 아직 쓰지 않은 유효한 연결 코드 */
    fun activeCode(): ConnectionCode?

    /** 연결 코드 받기 (#11): 6자리, 10분, 일회용, 이전 코드 무효 */
    fun issueCode(done: (ConnectionCode?) -> Unit)

    /** 고령자가 정한 보호자 호칭(guardianLabel) 바꾸기 */
    fun renameGuardian(guardianId: String, label: String, done: (Boolean) -> Unit)

    /** 고령자 쪽 해제 (#12): 보호자 앱에 보이는 해제 확인 코드가 맞아야 끊김 */
    fun disconnect(guardianId: String, releaseCode: String, done: (DisconnectResult) -> Unit)

    /**
     * 민감 알림 (#6). L1 은 어르신이 "예"를 골랐을 때만, L2·L3 는 바로 부름.
     * [level] L1~L3, [reason] 왜 알리는지 짧은 말 (서버가 보호자 앱에 보여 줌)
     */
    fun reportSensitiveEntry(
        appName: String,
        packageName: String,
        category: String,
        level: String,
        reason: String,
        done: (NoticeOutcome) -> Unit,
    )

    /**
     * SOS (#8): SOS 단추, "도와줘", 사기 의심 카드의 [보호자에게 연락]. 누를 때마다 보냄 (서버도 중복 방지 없음).
     * [category] "scam" 이면 서버가 L3(가장 급함)로. 화면 내용은 보내지 않음
     */
    fun reportSos(appName: String, packageName: String, category: String?, reason: String, done: (NoticeOutcome) -> Unit)

    /** 막힘 (#7): 같은 안내에서 45초 진행이 없어 어르신이 "알려 주세요" 를 고름 (안내당 1번) */
    fun reportStuck(appName: String, packageName: String, done: (NoticeOutcome) -> Unit)

    /**
     * 보호자 승인 요청 (#9, S3): "해봄이 계속 도와 드려도 될까요?" 를 연결된 가족에게 물음.
     * [action] 은 [Approvals.ACTION_GOV24_AUTH] 같은 `guide_` 값. 결과는 [ApprovalStart]
     */
    fun requestApproval(appName: String, packageName: String, action: String, category: String, done: (ApprovalStart) -> Unit)

    /** 승인 결과 조회 (#19). 못 받으면 null (다음 차례에 다시) */
    fun approvalStatus(id: String, done: (ApprovalState?) -> Unit)

    /** 승인 기다리기 취소 (#10). 이미 끝난 요청(409·404)은 무시 */
    fun cancelApproval(id: String)
}

internal object Family {
    /** 서버 주소가 있으면 [kr.haebom.elder.net.HaebomServer] 가 넣음. release 는 서버 주소가 정해지기 전까지 null */
    @Volatile
    var gateway: FamilyGateway? = null

    const val CODE_TTL_MS = 600_000L
    /** 같은 민감 알림·동의 질문을 다시 할 수 있기까지 (들어올 때 5분 지났으면 다시) */
    const val NOTICE_COOLDOWN_MS = 300_000L
}

/** 민감 앱 판단: 패키지 이름으로만 (앱 이름 단어는 오탐이 많아 쓰지 않음) */
internal object SensitiveApps {
    const val CATEGORY_FINANCE = "finance"
    const val CATEGORY_PUBLIC = "public"

    private val PUBLIC = setOf("kr.go.minwon.m")
    private val FINANCE_PACKAGES = setOf(
        "viva.republica.toss", "com.kakaobank.channel", "com.kbstar.kbbank", "com.shinhan.sbanking",
        "com.wooribank.smart.npib", "com.kebhana.hanapush", "nh.smart.banking", "com.nonghyup.nhallonebank",
        "com.ibk.android.ionebank", "com.kbankwith.smartbank",
    )
    private val FINANCE_WORDS = listOf("bank", "kbstar", "toss", "shinhan", "wooribank")

    /** 민감 앱이면 분류, 아니면 null */
    fun category(packageName: String?): String? {
        val pkg = packageName?.lowercase(Locale.ROOT) ?: return null
        return when {
            pkg in PUBLIC -> CATEGORY_PUBLIC
            pkg in FINANCE_PACKAGES || FINANCE_WORDS.any { it in pkg } -> CATEGORY_FINANCE
            else -> null
        }
    }
}
