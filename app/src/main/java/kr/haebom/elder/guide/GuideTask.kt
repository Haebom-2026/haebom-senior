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
            YOUTUBE -> YOUTUBE_SEARCH + YOUTUBE_RESULTS
            GOV24 -> AUTHENTICATION + GOV24_CERTIFICATE + GOV24_APPLY
        }

    /** 화면 안내 AI(#16)에 보내는 목표 */
    val aiGoal: String
        get() = when (this) {
            CONTACTS -> "ADD_CONTACT"
            YOUTUBE -> "YOUTUBE"
            GOV24 -> "GOV24"
        }

    /**
     * 단추로 시작해 말한 문장이 없을 때 AI 에 보내는 할 일 (비어 있으면 AI 가 "무엇을 할까요?" 하고 되묻기만 함)
     */
    fun defaultUtterance(query: String?): String = when (this) {
        CONTACTS -> "새 연락처를 추가해 줘"
        YOUTUBE -> if (query != null) "유튜브에서 $query 검색해 줘" else "유튜브에서 영상을 검색하게 도와줘"
        GOV24 -> "정부24에서 주민등록표 등본을 발급하게 도와줘. 로그인·본인인증은 내가 직접 하고, 끝나면 이어서 안내해 줘"
    }

    /** AI 에 보내는 slots (할 일을 분명히) */
    fun defaultSlots(query: String?): Map<String, String> = when (this) {
        CONTACTS -> mapOf("action" to "연락처 추가")
        YOUTUBE -> query?.let { mapOf("query" to it) }.orEmpty()
        GOV24 -> mapOf("service" to "주민등록표 등본(초본) 발급")
    }

    /** 해봄이 검색어를 대신 넣어 줄 수 있는 입력 칸 (resource id 끝부분) */
    val searchFieldIds: Set<String>
        get() = if (this == YOUTUBE) setOf("search_edit_text") else emptySet()

    /**
     * 지금 화면에서 할 일.
     * [query] 는 어르신이 말한 검색어(유튜브), [searchField] 는 검색창이 열려 있는지.
     */
    fun findStep(
        controls: List<ScreenControl>,
        hasPasswordField: Boolean,
        query: String? = null,
        searchField: Boolean = false,
        /** 검색어를 이미 넣었는지 (안 넣었으면 자동완성 입력 단추를 보여 줌) */
        queryFilled: Boolean = true,
    ): GuideStep {
        // 비밀번호 칸이 있거나, 정부24에서 로그인·인증을 고르는 단추가 있으면 직접 하시도록.
        // 눌리는 단추만 봄: 인증을 마친 뒤 화면에 '본인인증' 같은 글자만 남아 있으면 안내를 이어 감
        if (hasPasswordField || (this == GOV24 && controls.any { it.clickable && it.matches(AUTHENTICATION) })) {
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
            YOUTUBE -> youtubeStep(controls, query, searchField, queryFilled)
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

    private fun youtubeStep(controls: List<ScreenControl>, query: String?, searchField: Boolean, queryFilled: Boolean): GuideStep {
        val results = controls.firstOrNull { it.matches(YOUTUBE_RESULTS) }
        if (results != null) {
            // 말한 검색어의 결과면 끝. 예전에 검색해 둔 다른 결과 화면이면 위쪽 검색어 칸을 눌러 새로 검색
            if (query == null || normalize(query) in results.labels) return GuideStep(R.string.guide_youtube_results)
            return GuideStep(R.string.guide_youtube_new_search, results.takeIf { it.clickable }, query)
        }
        if (query == null) {
            return controlStep(
                controls, YOUTUBE_SEARCH,
                R.string.guide_youtube_search, R.string.guide_youtube_enter_query,
            )
        }
        if (searchField && !queryFilled) {
            // 검색창이 열림: 바로 넣지 않고 아래 "자동완성 입력" 단추를 어르신이 누르게
            return GuideStep(R.string.guide_youtube_tap_autofill, arg = query, chips = listOf(query))
        }
        if (searchField) {
            // 검색어를 넣어 드린 뒤: 같은 글자의 추천 검색어를 짚고, 없으면 키보드의 검색 단추
            val suggestion = controls.firstOrNull { it.clickable && normalize(query) in it.labels }
            return if (suggestion != null) {
                GuideStep(R.string.guide_youtube_pick_suggestion, suggestion, query)
            } else {
                GuideStep(R.string.guide_youtube_press_enter, arg = query)
            }
        }
        val search = controls.firstOrNull { it.clickable && it.matches(YOUTUBE_SEARCH) }
        return if (search != null) {
            GuideStep(R.string.guide_youtube_search_query, search, query)
        } else {
            GuideStep(R.string.guide_youtube_find_search, arg = query)
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
        /** 검색 결과 화면 위쪽의 검색어 칸 */
        private val YOUTUBE_RESULTS = setOf("search_query")
        private val GOV24_APPLY = setOf("발급하기", "신청하기")

        private val AUTHENTICATION = setOf(
            "본인인증", "본인확인", "간편인증", "공동인증서", "금융인증서",
            "비회원신청하기", "회원신청하기", "인증하기", "로그인이필요합니다.",
        )
        private val GOV24_CERTIFICATE = setOf(
            "주민등록표등본(초본)발급", "주민등록표등본발급", "주민등록등본",
            "주민등록등본(초본)", "주민등록표등본(초본)",
        )

        private val CONTACT_WORDS = listOf("연락처", "전화번호부", "주소록")
        private val PHONE_WORDS = listOf("전화번호", "휴대폰번호", "핸드폰번호", "폰번호", "번호")
        private val SAVE_WORDS = listOf("추가", "등록", "저장", "넣", "입력", "만들")

        /**
         * "연락처 추가", "새 연락처", "전화번호 등록하고 싶어", "번호 저장해 줘" 같은 말. 비밀번호·인증번호는 아님.
         * 추가·저장하는 말이 있어야 함: "연락처에서 홍길동 찾아 줘" 는 연락처 추가가 아니라 AI 에 맡김
         */
        private fun isContactCommand(normalized: String): Boolean {
            if (isPrivateCommand(normalized)) return false
            val saving = SAVE_WORDS.any { it in normalized } || "새연락처" in normalized
            return (CONTACT_WORDS.any { it in normalized } || PHONE_WORDS.any { it in normalized }) && saving
        }

        /** 앱이 정해진 순서로 안내할 수 있는 정부24 일 (지금은 주민등록표 등본·초본만) */
        private val GOV24_KNOWN = listOf("등본", "초본")

        fun fromCommand(command: String): GuideTask? {
            val normalized = normalize(command)
            return when {
                isContactCommand(normalized) -> CONTACTS
                "유튜브" in normalized || "youtube" in normalized -> YOUTUBE
                // 정부24 의 다른 일("건강보험 자격득실 떼 줘")은 앱이 순서를 모르므로 AI 가 말 그대로 안내
                GOV24_KNOWN.any { it in normalized } -> GOV24
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
                // 유튜브를 보던 중 "임영웅 찾아 줘" 는 분명히 유튜브 검색
                currentTask == YOUTUBE && listOf("검색", "찾아", "틀어", "보여", "재생").any { it in normalized } -> currentTask
                else -> null
            }
        }

        private val QUERY_APP_WORDS = Regex("""(?i)(유튜브|youtube)\s*(에서|에|으로|로)?""")
        /**
         * 요청하는 말이 시작되는 곳 ("검색", "찾아", "틀어", "보여 줘" …). 여기서부터 끝까지는 검색어가 아님.
         * "임영웅 검색 좀 해 줘", "임영웅 노래 찾아봐", "트로트 틀어줘요" 처럼 끝말이 달라도 앞부분만 남김
         */
        private val REQUEST_START = Regex(
            """\s*(을|를|좀|좀\s+)?\s*(검색|찾아|찾기|찾을|틀어|틀기|보여|재생|들려|알려|열어|켜|부탁)""",
        )
        private val QUERY_FILLERS = setOf("좀", "한번", "한", "번", "검색", "안내", "영상", "동영상", "해", "해줘", "줘", "주세요")

        const val GOAL_UNKNOWN = "UNKNOWN"
        const val GOAL_OPEN_APP = "OPEN_APP"
        const val GOAL_BANK = "BANK"

        private val OPEN_APP = Regex("""^\s*(.+?)\s*(앱|어플)?\s*(을|를)?\s*(좀\s*)?(열어|켜|실행|들어가)""")

        /** "카카오톡 열어 줘" → "카카오톡". 앱 열기 명령이 아니면 null */
        fun appSlot(command: String): String? =
            OPEN_APP.find(command)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 30 }

        /** 문장 전체가 "○○ (앱) (좀) 열어 줘" 뿐인 말 (뒤에 다른 할 일이 없음) */
        private val OPEN_ONLY = Regex(
            """^\s*(.+?)\s*(앱|어플)?\s*(을|를)?\s*(좀\s*)?(열어|켜|실행해|실행|들어가)\s*(줘|줘요|주세요|줄래|줄래요|봐|봐요|주라)?[\s.!?~]*$""",
        )

        /**
         * 앱을 열기만 하라는 말이면 그 앱 이름. "정부24 열어 줘", "유튜브 켜 줘", "연락처 앱 좀 열어 줘" → 앱만 열고
         * 그 뒤는 어르신에게 맡김 (등본·검색·연락처 추가 안내를 시작하지 않음).
         * "유튜브 켜서 임영웅 찾아 줘", "정부24에서 등본 열어 줘" 처럼 할 일이 더 있으면 null
         */
        fun openOnlyApp(command: String): String? {
            val name = OPEN_ONLY.find(command)?.groupValues?.get(1)?.trim() ?: return null
            if (name.isEmpty() || name.length > 20) return null
            if (listOf("에서", "에서는", "하고", "해서").any { it in name }) return null
            return name
        }

        /** 말한 앱 이름과 지금 앱 이름이 같은 앱인지 ("카카오톡" ↔ "카카오톡", "유튜브" ↔ "YouTube") */
        fun isSameApp(spoken: String, label: String): Boolean {
            val a = normalize(spoken).removeSuffix("앱")
            val b = normalize(label)
            if (a.isEmpty() || b.isEmpty()) return false
            val aliases = mapOf("유튜브" to "youtube", "크롬" to "chrome", "구글" to "google", "지메일" to "gmail")
            val alias = aliases[a]
            return a == b || (a.length >= 2 && (a in b || b in a)) || (alias != null && alias in b)
        }

        /**
         * 앱이 모르는 일을 AI 에 보낼 때의 목표와 slots. 할 일은 utterance(어르신이 한 말 그대로)가 정하고,
         * 여기서는 어느 앱 일인지만 알려 줌 (정해 둔 서류·검색어를 넣지 않음)
         */
        fun aiRequestFor(command: String): Pair<String, Map<String, String>> {
            val normalized = normalize(command)
            if ("정부24" in normalized || "정부이십사" in normalized) return GOV24.aiGoal to mapOf("app" to "정부24")
            val app = appSlot(command)
            return if (app != null) GOAL_OPEN_APP to mapOf("app" to app) else GOAL_UNKNOWN to emptyMap()
        }

        /**
         * 말한 명령에서 유튜브 검색어만 뽑습니다. 없으면 null.
         * "유튜브에 임영웅 검색해줘" → "임영웅", "유튜브에서 트로트 노래 틀어 줘" → "트로트 노래"
         */
        fun youtubeQuery(command: String): String? = searchWords(QUERY_APP_WORDS.replace(command, " "))

        /**
         * 말에서 검색어만: 요청하는 말("검색해 줘", "찾아봐" …) 앞부분, 끝의 을/를·꾸밈말 제외.
         * 검색어가 없으면 null. 자동완성 단추·검색 주소에 넣는 모든 검색어가 이 규칙을 거침
         */
        fun searchWords(text: String): String? {
            val trimmed = text.trim()
            // 처음 나오는 요청하는 말 앞까지만 (맨 앞이면 "검색해 줘" 처럼 검색어 없음)
            val head = trimmed.substring(0, REQUEST_START.find(trimmed)?.range?.first ?: trimmed.length)
            val words = head.split(Regex("""\s+"""))
                .map { it.trimEnd('?', '.', '!', '~') }
                .filter { it.isNotBlank() && it !in QUERY_FILLERS }
            val query = words.joinToString(" ").trim().removeSuffix("을").removeSuffix("를").trim()
            return query.takeIf { it.isNotEmpty() && it.length <= 40 }
        }

        fun isFinance(packageName: String?, command: String): Boolean {
            val packageLower = packageName.orEmpty().lowercase(Locale.ROOT)
            val normalized = normalize(command)
            return listOf("bank", "kbstar", "toss", "shinhan", "wooribank").any { it in packageLower } ||
                listOf("은행", "토스", "송금", "이체", "계좌").any { it in normalized }
        }

        /** "그만", "그만해 줘", "멈춰", "안내 그만", "이제 됐어" 처럼 안내를 멈추라는 말만 한 것 (다른 할 일이 붙으면 아님) */
        private val STOP = Regex(
            """^(이제)?(안내)?(좀)?(그만|그만둬|그만둘래|멈춰|멈춰라|중지|정지|취소|끝내|끝|됐어)(해|하자|할래|할래요|할게|할게요|할께|할께요|해줘|줘|주세요|요|해요|하세요|해라|라)?[.!~?]*$""",
        )

        fun isStopCommand(command: String): Boolean = STOP.matches(normalize(command))

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

/** [arg] 는 자막 문장에 끼워 넣을 글자 (예: 검색어) */
/** [chips]: 검색창 아래에 보여 줄 자동완성 입력 단추 글자 */
internal data class GuideStep(
    val caption: Int,
    val control: ScreenControl? = null,
    val arg: String? = null,
    val chips: List<String> = emptyList(),
)
