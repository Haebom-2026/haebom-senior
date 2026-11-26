package kr.haebom.elder.family

import android.content.Context
import android.os.SystemClock
import kr.haebom.elder.R
import kr.haebom.elder.guide.GuideCaption

/**
 * 보호자 알림 보내기 공통.
 * - 같은 일(키)은 5분에 한 번 (말한 명령 → 은행 앱 진입이 바로 이어져도 한 번, 5분 지나 다시 들어오면 다시)
 * - 결과를 어르신이 알아듣는 문장으로 바꿔 자막에 띄움
 */
internal object SensitiveAlerter {
    private val recent = mutableMapOf<String, Long>()
    var now: () -> Long = { SystemClock.elapsedRealtime() }

    /** 이 키로 5분 안에 알린 적이 없으면 true 를 돌려주고 지금 시각을 기록 */
    @Synchronized
    fun claim(key: String): Boolean {
        val time = now()
        val last = recent[key]
        if (last != null && time - last < Family.NOTICE_COOLDOWN_MS) return false
        recent[key] = time
        return true
    }

    /** L2·L3 처럼 동의 없이 바로 알릴 때 쓰는 키 (같은 분류는 5분에 한 번) */
    fun keyFor(assessment: Assessment, packageName: String?): String =
        if (assessment.category == Sensitivity.FINANCE) "finance" else "${assessment.category}:${packageName.orEmpty()}"

    /** 보호자에게 알리고, 결과 문장을 자막에 띄운 뒤 [done] 으로도 돌려줌 */
    fun send(
        context: Context,
        assessment: Assessment,
        appName: String,
        packageName: String,
        done: (String) -> Unit = {},
    ) {
        val gateway = Family.gateway
        if (gateway == null) {
            val text = context.getString(R.string.alert_unavailable)
            GuideCaption.post(text, GuideCaption.Tone.WARN)
            done(text)
            return
        }
        gateway.reportSensitiveEntry(
            appName = appName,
            packageName = packageName,
            category = assessment.category,
            level = assessment.level.wire,
            reason = assessment.reason,
        ) { outcome ->
            val text = message(context, outcome)
            GuideCaption.post(text, if (outcome.result == NoticeResult.CREATED) GuideCaption.Tone.DONE else GuideCaption.Tone.WARN)
            done(text)
        }
    }

    /**
     * SOS (#8): 보호자 앱에 바로 알림. 문자는 따로 (문자 앱에서 어르신이 보냄).
     * 결과는 자막으로 (보호자 연동이 없으면 조용히 넘어감: 문자는 그대로 감)
     */
    fun sos(context: Context, appName: String, packageName: String, scam: Boolean = false) {
        val gateway = Family.gateway ?: return
        val reason = context.getString(if (scam) R.string.sos_reason_scam else R.string.sos_reason)
        gateway.reportSos(appName, packageName, if (scam) "scam" else null, reason) { outcome ->
            val text = if (outcome.result == NoticeResult.CREATED) {
                context.getString(R.string.sos_notified, outcome.guardianLabels.joinToString(", ") { "$it 님" })
            } else {
                message(context, outcome)
            }
            GuideCaption.post(text, if (outcome.result == NoticeResult.CREATED) GuideCaption.Tone.DONE else GuideCaption.Tone.WARN)
        }
    }

    fun message(context: Context, outcome: NoticeOutcome): String = when (outcome.result) {
        NoticeResult.CREATED -> context.getString(R.string.consent_sent, outcome.guardianLabels.joinToString(", ") { "$it 님" })
        NoticeResult.DUPLICATE_SUPPRESSED -> context.getString(R.string.consent_duplicate)
        NoticeResult.NO_GUARDIANS -> context.getString(R.string.consent_no_guardians)
        NoticeResult.FAILED -> context.getString(R.string.consent_failed)
    }
}
