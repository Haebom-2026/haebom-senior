package kr.haebom.elder.guide

import android.content.Intent
import android.content.pm.PackageManager
import android.provider.ContactsContract
import kr.haebom.elder.R
import java.util.Locale

internal enum class GuideTask(val appPackage: String?) {
    CONTACTS(null),
    YOUTUBE("com.google.android.youtube"),
    GOV24("kr.go.minwon.m");

    fun newIntent(packageManager: PackageManager): Intent? {
        val destination = appPackage
        return if (destination == null) {
            Intent(Intent.ACTION_INSERT).setType(ContactsContract.Contacts.CONTENT_TYPE)
        } else {
            packageManager.getLaunchIntentForPackage(destination)
        }
    }

    fun findStep(controls: List<ScreenControl>, hasPasswordField: Boolean): GuideStep {
        if (hasPasswordField || (this == GOV24 && controls.any { it.matches(AUTHENTICATION) })) {
            return GuideStep(R.string.guide_authentication_stop)
        }

        return when (this) {
            CONTACTS -> {
                val backupPrompt = controls.any { it.matches(setOf("addaccount", "계정추가")) }
                if (backupPrompt) {
                    controlStep(
                        controls, setOf("cancel", "취소"),
                        R.string.guide_contact_skip_backup, R.string.guide_contact_skip_backup,
                    )
                } else {
                    controlStep(
                        controls, setOf("저장", "완료", "save", "done", "editor_menu_save_button"),
                        R.string.guide_contact_save, R.string.guide_contact_entry,
                    )
                }
            }
            YOUTUBE -> controlStep(
                controls, setOf("검색", "search", "menu_item_search"),
                R.string.guide_youtube_search, R.string.guide_youtube_enter_query,
            )
            GOV24 -> {
                val service = controls.firstOrNull { it.clickable && it.matches(GOV24_CERTIFICATE) }
                val certificateScreen = controls.any { it.matches(GOV24_CERTIFICATE) }
                val apply = controls.firstOrNull {
                    it.clickable && it.matches(setOf("발급하기", "신청하기"))
                }
                when {
                    certificateScreen && apply != null -> GuideStep(R.string.guide_gov24_apply, apply)
                    service != null -> GuideStep(R.string.guide_gov24_open_certificate, service)
                    else -> GuideStep(R.string.guide_gov24_find_certificate)
                }
            }
        }
    }

    companion object {
        private val AUTHENTICATION = setOf(
            "본인인증", "본인확인", "간편인증", "공동인증서", "금융인증서",
            "비회원신청하기", "회원신청하기", "인증하기", "로그인이필요합니다.",
        )
        private val GOV24_CERTIFICATE = setOf(
            "주민등록표등본(초본)발급", "주민등록표등본발급", "주민등록등본",
            "주민등록등본(초본)", "주민등록표등본(초본)",
        )

        fun fromCommand(command: String): GuideTask? {
            val normalized = normalize(command)
            return when {
                "연락처" in normalized || "전화번호추가" in normalized -> CONTACTS
                "유튜브" in normalized || "youtube" in normalized -> YOUTUBE
                "등본" in normalized || "정부24" in normalized -> GOV24
                else -> null
            }
        }

        fun isHelpCommand(command: String): Boolean {
            val normalized = normalize(command)
            return listOf("도와줘", "도와주세요", "도움요청", "sos").any { it in normalized }
        }

        fun isScamCommand(command: String): Boolean {
            val normalized = normalize(command)
            val impersonation = listOf("경찰", "검찰", "금감원").any { it in normalized }
            val moneyMovement = listOf("돈", "송금", "이체", "계좌").any { it in normalized }
            return listOf("안전계좌", "원격제어", "팀뷰어", "애니데스크").any {
                it in normalized
            } || (impersonation && moneyMovement)
        }

        private fun controlStep(
            controls: List<ScreenControl>,
            labels: Set<String>,
            caption: Int,
            fallback: Int,
        ): GuideStep {
            val target = controls.firstOrNull { it.clickable && it.matches(labels) }
            return if (target == null) GuideStep(fallback) else GuideStep(caption, target)
        }

        internal fun normalize(value: String): String =
            value.lowercase(Locale.ROOT).filterNot(Char::isWhitespace)
    }
}

internal data class GuideStep(val caption: Int, val control: ScreenControl? = null)
