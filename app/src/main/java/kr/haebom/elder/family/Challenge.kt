package kr.haebom.elder.family

import org.json.JSONObject
import java.time.Instant
import java.util.Locale

/**
 * 가족의 도전 (S4 제안, 2026-11-27 보호자 앱 담당 문서). 고령자 앱 쪽 규칙만 (안드로이드 없이 단위 테스트).
 *
 * 보호자가 어르신께 해 볼 만한 일을 보내고, 어르신이 스스로 해내면 가족이 칭찬함.
 * - 숙제가 아님: 하실지는 어르신이 정함. 재촉·반복 알림 없음
 * - "다음에 할게요"는 거절이 아님 (보호자에게 알리지 않음)
 * - 실패·시간 제한·순위·연속 기록 없음. 도우미 안내를 받아도 성공
 * - 서버로 가는 것은 누른 단추(해 볼게요·다음에·해냈어요)와 withHelp 뿐
 */
internal enum class ChallengeStatus(val wire: String) {
    SENT("sent"), LATER("later"), ACCEPTED("accepted"), DONE("done");

    companion object {
        fun of(value: String?): ChallengeStatus? = entries.firstOrNull { it.wire == value }
    }
}

internal data class Challenge(
    val id: String,
    val title: String,
    val hint: String,
    /** 보낸 보호자 (guardianId) */
    val suggestedBy: String,
    val status: ChallengeStatus,
    val createdAt: Long,
    val completedAt: Long? = null,
    val withHelp: Boolean = false,
    /** 보호자 id → 칭찬 문구 */
    val cheers: Map<String, String> = emptyMap(),
) {
    /** 어르신이 아직 해 볼 수 있는 도전 (보냄·다음에·하는 중) */
    val open: Boolean get() = status != ChallengeStatus.DONE
}

internal object Challenges {
    /** 서버 연결이 있으면 [kr.haebom.elder.net.HaebomServer] 가 넣음 */
    @Volatile
    var gateway: ChallengeGateway? = null

    const val MAX_TITLE = 30
    const val MAX_HINT = 40

    /**
     * 보호자를 사칭한 도전 대비: 돈·인증 말이 든 도전은 어르신께 보여 주지 않음 (서버도 막음).
     * 보호자 앱 Mock 과 같은 목록, 띄어쓰기를 없애고 대문자로 비교
     */
    private val BLOCKED = listOf(
        "송금", "이체", "입금", "계좌", "결제", "대출", "돈보내", "돈빌려", "비밀번호", "비번", "인증번호", "카드번호",
        "보안카드", "OTP", "주민번호", "주민등록번호", "공인인증", "상품권",
    )

    fun isBlocked(title: String, hint: String): Boolean {
        val text = (title + hint).filterNot(Char::isWhitespace).uppercase(Locale.ROOT)
        return BLOCKED.any { it.uppercase(Locale.ROOT) in text }
    }

    /** 보여 줄 수 있는 도전만 (막는 말·길이 넘침·빈 이름 제외) */
    fun safe(list: List<Challenge>): List<Challenge> = list.filter {
        it.title.isNotBlank() && it.title.length <= MAX_TITLE && it.hint.length <= MAX_HINT && !isBlocked(it.title, it.hint)
    }

    /** 홈 카드·하나씩 보여 줄 순서: 아직 해 볼 수 있는 도전을 받은 순서대로 */
    fun openInOrder(list: List<Challenge>): List<Challenge> = safe(list).filter { it.open }.sortedBy { it.createdAt }

    /** 아직 보지 않은 칭찬 (도전 id, 보호자 id, 문구). [seen] 은 [cheerKey] 모음 */
    fun unseenCheers(list: List<Challenge>, seen: Set<String>): List<Triple<Challenge, String, String>> =
        safe(list).filter { it.status == ChallengeStatus.DONE }.sortedBy { it.completedAt ?: it.createdAt }
            .flatMap { c -> c.cheers.map { (guardian, message) -> Triple(c, guardian, message) } }
            .filter { (c, guardian, message) -> cheerKey(c.id, guardian, message) !in seen }

    /** 칭찬을 봤다는 표시 (보호자가 문구를 바꾸면 다시 보여 줌) */
    fun cheerKey(challengeId: String, guardianId: String, message: String): String = "$challengeId|$guardianId|$message"

    /** "딸" → "딸이", "배우자" → "배우자가" (받침에 따라). 한글이 아니면 "이(가)" */
    fun subject(label: String): String {
        val last = label.trim().lastOrNull() ?: return label
        if (last !in '가'..'힣') return "${label}이(가)"
        return if ((last - '가') % 28 != 0) "${label}이" else "${label}가"
    }

    /** GET /v1/elder/challenges 응답 → 목록. 형식이 틀린 항목은 건너뜀 */
    fun parse(json: JSONObject?): List<Challenge> {
        val array = json?.optJSONArray("items") ?: json?.optJSONArray("challenges") ?: return emptyList()
        return (0 until array.length()).mapNotNull { i -> runCatching { parseOne(array.getJSONObject(i)) }.getOrNull() }
    }

    fun parseOne(item: JSONObject): Challenge? {
        val status = ChallengeStatus.of(item.optString("status")) ?: return null
        val cheers = item.optJSONObject("cheers")?.let { obj ->
            obj.keys().asSequence().associateWith { obj.optString(it) }.filterValues { it.isNotBlank() }
        }.orEmpty()
        return Challenge(
            id = item.getString("id"),
            title = item.optString("title").trim(),
            hint = item.optString("hint").trim(),
            suggestedBy = item.optString("suggestedBy"),
            status = status,
            createdAt = time(item.optString("createdAt")) ?: 0L,
            completedAt = time(item.optString("completedAt")),
            withHelp = item.optBoolean("withHelp"),
            cheers = cheers,
        )
    }

    private fun time(value: String?): Long? =
        value?.takeIf { it.isNotBlank() && it != "null" }?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
}

/** 어르신이 누른 단추의 결과 */
internal enum class ChallengeResult { OK, NOT_ALLOWED, FAILED }

/**
 * 도전 서버 창구 (S4 제안 경로). 고령자는 목록 보기와 단추 셋만.
 *   GET  /v1/elder/challenges
 *   POST /v1/elder/challenges/{id}/accept | later | done {withHelp}
 */
internal interface ChallengeGateway {
    /** 마지막으로 받은 목록 (아직 못 받았으면 빈 목록) */
    fun cached(): List<Challenge>

    /** 서버에서 다시 받음 (FCM kind: challenge, 화면 들어올 때). 실패하면 null */
    fun refresh(done: (List<Challenge>?) -> Unit = {})

    /** 목록이 바뀔 때마다 */
    fun observe(listener: () -> Unit): AutoCloseable

    fun accept(id: String, done: (ChallengeResult) -> Unit)
    fun later(id: String, done: (ChallengeResult) -> Unit)
    fun done(id: String, withHelp: Boolean, done: (ChallengeResult) -> Unit)
}
