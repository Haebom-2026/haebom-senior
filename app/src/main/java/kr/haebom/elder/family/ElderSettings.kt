package kr.haebom.elder.family

import android.content.Context

/** SOS 문자를 받을 사람 한 명 */
internal data class SosContact(val name: String, val phone: String)

/**
 * 폰에만 두는 설정. 서버로 보내지 않습니다.
 * - SOS 버튼 켜기/끄기
 * - 보호자 전화번호 (연결된 보호자는 guardianId 별로, 연결 전에는 직접 등록한 1명)
 */
internal class ElderSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("elder_settings", Context.MODE_PRIVATE)

    /** 화면 위 SOS 버튼과 메인 화면 도움 요청 버튼을 보일지 */
    var sosEnabled: Boolean
        get() = prefs.getBoolean(KEY_SOS, true)
        set(value) = prefs.edit().putBoolean(KEY_SOS, value).apply()

    /** 민감 앱(은행·정부24 등)을 열면 "보호자에게 알릴까요?" 물어보기 */
    var sensitiveAlerts: Boolean
        get() = prefs.getBoolean(KEY_SENSITIVE, true)
        set(value) = prefs.edit().putBoolean(KEY_SENSITIVE, value).apply()

    /** 안내 단계 (처음/조금 익숙/잘 함). 어르신이 메인 화면에서 고름 */
    var proficiency: kr.haebom.elder.guide.Proficiency
        get() = kr.haebom.elder.guide.Proficiency.of(prefs.getString(KEY_PROFICIENCY, null))
        set(value) = prefs.edit().putString(KEY_PROFICIENCY, value.name).putInt(KEY_DONE_AT_LEVEL, 0).apply()

    /** 지금 단계에서 안내를 끝까지 마친 횟수 (단계를 바꾸면 0부터) */
    val doneAtLevel: Int get() = prefs.getInt(KEY_DONE_AT_LEVEL, 0)

    fun countDone() {
        prefs.edit().putInt(KEY_DONE_AT_LEVEL, doneAtLevel + 1).apply()
    }

    /** 이 단계에서 "안내를 줄여 볼까요?"를 이미 물었는지 */
    fun suggestedAt(level: kr.haebom.elder.guide.Proficiency): Boolean = prefs.getBoolean(KEY_SUGGESTED + level.name, false)

    fun markSuggested(level: kr.haebom.elder.guide.Proficiency) {
        prefs.edit().putBoolean(KEY_SUGGESTED + level.name, true).apply()
    }

    /** 이 기기의 고유 ID (서버 #4 기기 등록, 디버그 뷰어 heartbeat 에 같은 값) */
    val deviceId: String
        get() = prefs.getString(KEY_DEVICE, null) ?: java.util.UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_DEVICE, it).apply()
        }

    /**
     * 고령자가 바꾼 보호자 호칭. 서버에 호칭 변경 API 가 생기기 전까지 폰에만 둠
     * (생기면 서버 guardianLabel 로 보냄)
     */
    fun labelOverride(guardianId: String): String? = prefs.getString(KEY_LABEL + guardianId, null)

    fun setLabelOverride(guardianId: String, label: String) {
        prefs.edit().putString(KEY_LABEL + guardianId, label.trim()).apply()
    }

    fun phoneFor(guardianId: String): String = prefs.getString(KEY_PHONE + guardianId, "").orEmpty()

    fun setPhone(guardianId: String, phone: String) {
        prefs.edit().putString(KEY_PHONE + guardianId, normalizePhone(phone)).apply()
    }

    fun forget(guardianId: String) {
        prefs.edit().remove(KEY_PHONE + guardianId).remove(KEY_LABEL + guardianId).apply()
    }

    /** 연결 전이라도 SOS 문자를 받을 보호자를 직접 등록 (예: 김준표, 010-1234-5678) */
    var manualContact: SosContact?
        get() {
            val name = prefs.getString(KEY_MANUAL_NAME, null) ?: return null
            return SosContact(name, prefs.getString(KEY_MANUAL_PHONE, "").orEmpty())
        }
        set(value) {
            prefs.edit().apply {
                if (value == null) {
                    remove(KEY_MANUAL_NAME); remove(KEY_MANUAL_PHONE)
                } else {
                    putString(KEY_MANUAL_NAME, value.name.trim()); putString(KEY_MANUAL_PHONE, normalizePhone(value.phone))
                }
            }.apply()
        }

    /** SOS 문자 받을 사람들: 연결된 보호자 중 번호가 있는 사람 + 직접 등록한 사람 */
    fun sosContacts(links: List<FamilyLink>): List<SosContact> {
        val linked = links.mapNotNull { link ->
            phoneFor(link.guardianId).takeIf { isValidPhone(it) }?.let { SosContact(link.labelForElder, it) }
        }
        val manual = manualContact?.takeIf { isValidPhone(it.phone) }
        return (linked + listOfNotNull(manual)).distinctBy { it.phone }
    }

    companion object {
        private const val KEY_SOS = "sos_enabled"
        private const val KEY_SENSITIVE = "sensitive_alerts"
        private const val KEY_PHONE = "phone_"
        private const val KEY_LABEL = "label_"
        private const val KEY_DEVICE = "device_id"
        private const val KEY_MANUAL_NAME = "manual_name"
        private const val KEY_MANUAL_PHONE = "manual_phone"
        private const val KEY_PROFICIENCY = "proficiency"
        private const val KEY_DONE_AT_LEVEL = "done_at_level"
        private const val KEY_SUGGESTED = "suggested_"

        fun normalizePhone(value: String): String = value.filter { it.isDigit() || it == '+' }

        fun isValidPhone(value: String): Boolean = normalizePhone(value).matches(Regex("\\+?[0-9]{3,15}"))

        /** 01012345678 → 010-1234-5678 (보여 줄 때만) */
        fun displayPhone(value: String): String {
            val digits = normalizePhone(value)
            return when {
                digits.length == 11 && digits.startsWith("01") -> "${digits.take(3)}-${digits.substring(3, 7)}-${digits.takeLast(4)}"
                digits.length == 10 && digits.startsWith("02") -> "${digits.take(2)}-${digits.substring(2, 6)}-${digits.takeLast(4)}"
                digits.length == 10 -> "${digits.take(3)}-${digits.substring(3, 6)}-${digits.takeLast(4)}"
                else -> digits
            }
        }
    }
}
