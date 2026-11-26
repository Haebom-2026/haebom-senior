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

    /** 앱이 없을 때 대신 여는 웹 주소 (크롬으로 엶) */
    val webUrl: String?
        get() = if (this == GOV24) "https://plus.gov.kr" else null

    fun newIntent(packageManager: PackageManager): Intent? {
        val destination = appPackage
        return if (destination == null) {
            Intent(Intent.ACTION_INSERT).setType(ContactsContract.Contacts.CONTENT_TYPE)
        } else {
            packageManager.getLaunchIntentForPackage(destination)
                ?: webUrl?.let { kr.haebom.elder.Browser.intent(packageManager, it) }
        }
    }

    /** 자막에 쓰는 앱 이름 */
    val nameText: Int
        get() = when (this) {
            CONTACTS -> R.string.task_contacts_name
            YOUTUBE -> R.string.task_youtube_name
            GOV24 -> R.string.task_gov24_name
        }

    /** 안내를 시작할 때 "이렇게 할게요" 문장 */
    fun nextStepText(): Int = when (this) {
        CONTACTS -> R.string.task_contacts_next
        YOUTUBE -> R.string.task_youtube_next
        GOV24 -> R.string.task_gov24_next
    }

    val allowedLabels: Set<String>
        get() = when (this) {
            CONTACTS -> CONTACT_ADD + CONTACT_SAVE + CONTACT_BACKUP + CONTACT_CANCEL
            YOUTUBE -> YOUTUBE_SEARCH
            GOV24 -> AUTHENTICATION + GOV24_CERTIFICATE + GOV24_APPLY
        }

    /** 해봄이 검색어를 대신 넣어 줄 수 있는 입력 칸 (resource id 끝부분) */
    val searchFieldIds: Set<String>
        get() = if (this == YOUTUBE) setOf("search_edit_text") else emptySet()

    fun findStep(controls: List<ScreenControl>, hasPasswordField: Boolean): GuideStep {
        if (hasPasswordField || (this == GOV24 && controls.any { it.matches(AUTHENTICATION) })) {
            return GuideStep(R.string.guide_authentication_stop)
        }

        return when (this) {
            CONTACTS -> {
                val backupPrompt = controls.any { it.matches(CONTACT_BACKUP) }
                if (backupPrompt) {
                    controlStep(
                        controls, CONTACT_CANCEL,
                        R.string.guide_contact_skip_backup, R.string.guide_contact_skip_backup,
                    )
                } else {
                    val add = controls.firstOrNull { it.clickable && it.matches(CONTACT_ADD) }
                    if (add != null) GuideStep(R.string.guide_contact_add, add) else controlStep(
                        controls, CONTACT_SAVE, R.string.guide_contact_save, R.string.guide_contact_entry,
                    )
                }
            }
            YOUTUBE -> controlStep(
                controls, YOUTUBE_SEARCH,
                R.string.guide_youtube_search, R.string.guide_youtube_enter_query,
            )
            GOV24 -> {
                val service = controls.firstOrNull { it.clickable && it.matches(GOV24_CERTIFICATE) }
                val certificateScreen = controls.any { it.matches(GOV24_CERTIFICATE) }
                val apply = controls.firstOrNull {
                    it.clickable && it.matches(GOV24_APPLY)
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
        private val CONTACT_PACKAGES = setOf(
            "com.android.contacts", "com.google.android.contacts", "com.samsung.android.app.contacts",
        )
        private val CONTACT_ADD = setOf("추가", "새연락처", "연락처추가", "createcontact", "newcontact", "addcontact")
        private val CONTACT_SAVE = setOf("저장", "완료", "save", "done", "editor_menu_save_button")
        private val CONTACT_BACKUP = setOf("addaccount", "계정추가")
        private val CONTACT_CANCEL = setOf("cancel", "취소")
        private val YOUTUBE_SEARCH = setOf("검색", "search", "menu_item_search")
        private val GOV24_APPLY = setOf("발급하기", "신청하기")

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

        fun forPackage(packageName: String?): GuideTask? = when {
            // 크롬에서 정부24 웹을 안내 중이면 크롬도 정부24 안내로 봄
            packageName == kr.haebom.elder.Browser.CHROME && GuideSession.active?.task == GOV24 -> GOV24
            packageName in CONTACT_PACKAGES -> CONTACTS
            packageName != null && packageName == YOUTUBE.appPackage -> YOUTUBE
            packageName != null && packageName == GOV24.appPackage -> GOV24
            else -> null
        }

        fun forCommand(command: String, foreground: String?): GuideTask? {
            fromCommand(command)?.let { return it }
            val currentTask = forPackage(foreground) ?: return null
            val normalized = normalize(command)
            val asksForScreenGuide = listOf(
                "이화면", "지금화면", "어디눌러", "무엇을눌러", "안내해",
            ).any { it in normalized }
            return when {
                asksForScreenGuide -> currentTask
                currentTask == YOUTUBE && "검색" in normalized -> currentTask
                else -> null
            }
        }

        fun isFinance(packageName: String?, command: String): Boolean {
            val packageLower = packageName.orEmpty().lowercase(Locale.ROOT)
            val normalized = normalize(command)
            return listOf("bank", "kbstar", "toss", "shinhan", "wooribank").any { it in packageLower } ||
                listOf("은행", "토스", "송금", "이체", "계좌").any { it in normalized }
        }

        fun isPrivateCommand(command: String): Boolean =
            listOf("비밀번호", "비번", "인증번호", "주민등록번호", "보안코드").any { it in normalize(command) }

        fun isHelpCommand(command: String): Boolean {
            val normalized = normalize(command)
            return normalized in setOf("도와줘", "도와주세요", "도움요청", "sos") ||
                listOf("문자보내", "보호자에게연락", "긴급도움").any { it in normalized }
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
